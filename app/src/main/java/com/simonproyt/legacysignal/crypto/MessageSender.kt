package com.simonproyt.legacysignal.crypto

import android.util.Base64
import android.util.Log
import com.simonproyt.legacysignal.api.SignalApi
import com.simonproyt.legacysignal.api.OutgoingPushMessage
import com.simonproyt.legacysignal.api.OutgoingPushMessageList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.metadata.SealedSessionCipher
import org.signal.libsignal.metadata.certificate.SenderCertificate
import java.util.UUID

class MessageSender(
    private val api: SignalApi,
    private val store: SharedPrefsSignalProtocolStore,
    private val authHeader: String
) {
    suspend fun sendMessage(
        destinationUuid: String,
        messageBody: String,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                
                // Fetch prekeys to get all active devices if we don't have sessions, or if we are retrying
                // To support multiple devices without caching, we'll just fetch prekeys on attempt 2 or if no primary session exists
                val fetchPrekeys = attempt > 1 || !store.containsSession(SignalProtocolAddress(destinationUuid, 1))
                
                val preKeyResponse = if (fetchPrekeys) {
                    val response = api.getPreKeys(authHeader, destinationUuid).execute()
                    if (!response.isSuccessful || response.body() == null) {
                        Log.e("MessageSender", "Failed to fetch prekeys for $destinationUuid: ${response.code()}")
                        return@withContext false
                    }
                    response.body()!!
                } else null

                // If we didn't fetch prekeys, we assume we're just sending to device 1 (simplification)
                // In a robust implementation, we would store all known device IDs.
                val activeDeviceIds = if (preKeyResponse != null) {
                    preKeyResponse.devices.map { it.deviceId }
                } else {
                    val sessions = store.getSubDeviceSessions(destinationUuid)
                    if (sessions.isEmpty()) listOf(1) else sessions
                }
                
                val pushMessages = mutableListOf<OutgoingPushMessage>()
                
                var certBytes = store.getDeliveryCertificate()
                if (certBytes != null) {
                    try {
                        SenderCertificate(certBytes)
                    } catch (e: Exception) {
                        Log.w("MessageSender", "Cached delivery certificate is invalid, clearing cache.")
                        certBytes = null
                    }
                }

                if (certBytes == null) {
                    val certResponse = api.getDeliveryCertificate(authHeader).execute()
                    if (certResponse.isSuccessful) {
                        val certB64 = certResponse.body()?.certificate
                        if (certB64 != null) {
                            certBytes = Base64.decode(certB64, Base64.NO_WRAP)
                            store.saveDeliveryCertificate(certBytes!!)
                        }
                    } else {
                        Log.w("MessageSender", "Failed to fetch delivery certificate: ${certResponse.code()}")
                    }
                }

                val senderCertificate = certBytes?.let {
                    try {
                        SenderCertificate(it)
                    } catch (e: Exception) {
                        Log.e("MessageSender", "Invalid certificate", e)
                        null
                    }
                }
                
                for (deviceId in activeDeviceIds) {
                    val address = SignalProtocolAddress(destinationUuid, deviceId)
                    
                    if (!store.containsSession(address)) {
                        if (preKeyResponse == null) continue // Should not happen
                        
                        val device = preKeyResponse.devices.firstOrNull { it.deviceId == deviceId } ?: continue
                        
                        Log.d("MessageSender", "No session for $destinationUuid:$deviceId, building...")
                        
                        val identityKey = IdentityKey(Base64.decode(preKeyResponse.identityKey, Base64.NO_WRAP))
                        val signedPreKeyPubKey = ECPublicKey(Base64.decode(device.signedPreKey?.publicKey, Base64.NO_WRAP))
                        val signedPreKeySig = Base64.decode(device.signedPreKey?.signature, Base64.NO_WRAP)
                        val preKeyPubKey = device.preKey?.publicKey?.let { ECPublicKey(Base64.decode(it, Base64.NO_WRAP)) }
                        val preKeyId = device.preKey?.keyId ?: 16777215
                        val signedPreKeyId = device.signedPreKey?.keyId ?: 0

                        val kyberPreKey = device.pqPreKey ?: throw IllegalStateException("No kyber prekey for device")
                        val kyberBytes = Base64.decode(kyberPreKey.publicKey, Base64.NO_WRAP)
                        val kyberKey = KEMPublicKey(kyberBytes, 0, kyberBytes.size)
                        val kyberSig = Base64.decode(kyberPreKey.signature, Base64.NO_WRAP)
                        
                        val bundle = PreKeyBundle(
                            device.registrationId,
                            device.deviceId,
                            preKeyId,
                            preKeyPubKey,
                            signedPreKeyId,
                            signedPreKeyPubKey,
                            signedPreKeySig,
                            identityKey,
                            kyberPreKey.keyId ?: 0,
                            kyberKey,
                            kyberSig
                        )

                        val builder = SessionBuilder(store, address)
                        builder.process(bundle)
                        Log.d("MessageSender", "Session built for $destinationUuid:$deviceId")
                    }

                    // Create Content proto
                    val dataMessageBuilder = com.simonproyt.legacysignal.api.push.SignalServiceProtos.DataMessage.newBuilder()
                        .setTimestamp(timestamp)
                        .setBody(messageBody)
                        
                    // Retrieve and append profile key
                    val profileKeyBytes = com.simonproyt.legacysignal.CredentialsManager.getProfileKey(store.context)
                    if (profileKeyBytes != null) {
                        Log.i("MessageSender", "Attaching ProfileKey of size ${profileKeyBytes.size} to message")
                        dataMessageBuilder.setProfileKey(com.google.protobuf.ByteString.copyFrom(profileKeyBytes))
                    } else {
                        Log.w("MessageSender", "ProfileKey is NULL, NOT attaching to message")
                    }
                    
                    val content = com.simonproyt.legacysignal.api.push.SignalServiceProtos.Content.newBuilder()
                        .setDataMessage(dataMessageBuilder.build())
                        .build()

                    val contentBytes = content.toByteArray()
                    val paddedBytes = ByteArray(contentBytes.size + 1)
                    System.arraycopy(contentBytes, 0, paddedBytes, 0, contentBytes.size)
                    paddedBytes[contentBytes.size] = 0x80.toByte()

                    var msgType = 1
                    val ciphertextMsgBytes = if (senderCertificate != null) {
                        val localUuid = UUID.fromString(senderCertificate.senderUuid)
                        val localE164 = senderCertificate.senderE164.orElse(null)
                        val localDeviceId = senderCertificate.senderDeviceId
                        val cipher = SealedSessionCipher(store, localUuid, localE164, localDeviceId)
                        msgType = 6 // UNIDENTIFIED_SENDER
                        cipher.encrypt(address, senderCertificate, paddedBytes)
                    } else {
                        val cipher = SessionCipher(store, address)
                        val ciphertextMsg = cipher.encrypt(paddedBytes)
                        msgType = if (ciphertextMsg.type == CiphertextMessage.PREKEY_TYPE) 3 else 1
                        ciphertextMsg.serialize()
                    }
                    
                    val remoteRegistrationId = try {
                        store.loadSession(address).remoteRegistrationId
                    } catch (e: Exception) {
                        0
                    }

                    pushMessages.add(OutgoingPushMessage(
                        type = msgType,
                        destinationDeviceId = deviceId,
                        destinationRegistrationId = remoteRegistrationId,
                        content = Base64.encodeToString(ciphertextMsgBytes, Base64.NO_WRAP)
                    ))
                }

                if (pushMessages.isEmpty()) {
                    Log.e("MessageSender", "No devices to send to!")
                    return@withContext false
                }

                val pushList = OutgoingPushMessageList(
                    destination = destinationUuid,
                    timestamp = timestamp,
                    messages = pushMessages,
                    online = false,
                    urgent = true
                )

                val response = api.sendMessage(authHeader, destinationUuid, false, pushList).execute()
                if (response.isSuccessful) {
                    Log.d("MessageSender", "Message sent successfully to $destinationUuid")
                    return@withContext true
                } else if (response.code() == 410 || response.code() == 409) {
                    Log.w("MessageSender", "Stale device (410/409), deleting sessions and retrying...")
                    // We just delete all sessions we tried to send to, and retry (which will fetch new prekeys)
                    for (msg in pushMessages) {
                        store.deleteSession(SignalProtocolAddress(destinationUuid, msg.destinationDeviceId))
                    }
                    continue
                } else {
                    Log.e("MessageSender", "Failed to send message: ${response.code()} ${response.errorBody()?.string()}")
                    return@withContext false
                }
            }
            
            return@withContext false

        } catch (e: Exception) {
            Log.e("MessageSender", "Exception sending message", e)
            return@withContext false
        }
    }

    suspend fun sendImageMessage(
        destinationUuid: String,
        imageBytes: ByteArray,
        caption: String = "",
        timestamp: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i("MessageSender", "Encrypting image attachment of size ${imageBytes.size} bytes")
            val encrypted = AttachmentCipher.encrypt(imageBytes)

            // Step 1: Request attachment upload form from server
            var uploadTimestamp = System.currentTimeMillis()
            var cdnKey: String = "attachments/${UUID.randomUUID()}"
            var cdnNumber = 2
            var uploadSucceeded = false

            try {
                var formResp = api.getAttachmentUploadFormV4(authHeader).execute()
                if (!formResp.isSuccessful || formResp.body() == null) {
                    formResp = api.getAttachmentUploadFormV3(authHeader).execute()
                }
                if (!formResp.isSuccessful || formResp.body() == null) {
                    formResp = api.getAttachmentUploadFormV2(authHeader).execute()
                }
                if (!formResp.isSuccessful || formResp.body() == null) {
                    formResp = api.getAttachmentUploadForm(authHeader).execute()
                }

                if (formResp.isSuccessful && formResp.body() != null) {
                    val formJsonStr = formResp.body()!!.string()
                    Log.i("MessageSender", "Attachment form received: $formJsonStr")
                    val formJson = org.json.JSONObject(formJsonStr)

                    if (formJson.has("key")) {
                        cdnKey = formJson.getString("key")
                    }
                    if (formJson.has("cdnNumber")) {
                        cdnNumber = formJson.getInt("cdnNumber")
                    } else if (formJson.has("cdn")) {
                        cdnNumber = formJson.getInt("cdn")
                    }
                    if (formJson.has("uploadTimestamp")) {
                        uploadTimestamp = formJson.getLong("uploadTimestamp")
                    }

                    val okHttpClientBuilder = okhttp3.OkHttpClient.Builder()
                    com.simonproyt.legacysignal.api.ConscryptHelper.configureOkHttp(store.context, okHttpClientBuilder)
                    val okHttpClient = okHttpClientBuilder.build()

                    if (formJson.has("signedUploadLocation")) {
                        val uploadLocation = formJson.getString("signedUploadLocation")
                        val headersObj = if (formJson.has("headers")) formJson.getJSONObject("headers") else null

                        Log.i("MessageSender", "Performing TUS attachment upload to $uploadLocation, ciphertext size: ${encrypted.ciphertext.size}")

                        // TUS Step 1: POST to create upload (empty body)
                        val createReqBuilder = okhttp3.Request.Builder()
                            .url(uploadLocation)
                            .addHeader("Tus-Resumable", "1.0.0")
                            .addHeader("Upload-Length", encrypted.ciphertext.size.toString())
                            .addHeader("Content-Type", "application/offset+octet-stream")
                            .post(okhttp3.RequestBody.create(null, ByteArray(0)))

                        // Add all server-provided headers (Authorization, Upload-Metadata, etc.)
                        if (headersObj != null) {
                            val hKeys = headersObj.keys()
                            while (hKeys.hasNext()) {
                                val hk = hKeys.next()
                                createReqBuilder.addHeader(hk, headersObj.getString(hk))
                            }
                        }

                        val createResp = okHttpClient.newCall(createReqBuilder.build()).execute()
                        val createCode = createResp.code()
                        val locationHeader = createResp.header("Location")
                        val createBody = createResp.body()?.string() ?: ""
                        Log.i("MessageSender", "TUS create response: $createCode, Location: $locationHeader, body: $createBody")

                        if (createCode == 201 || createResp.isSuccessful) {
                            // TUS Step 2: PATCH the data to the Location URL
                            val patchUrl = when {
                                locationHeader == null -> uploadLocation
                                locationHeader.startsWith("http") -> locationHeader
                                else -> {
                                    // Relative URL - construct absolute from uploadLocation base
                                    val baseUrl = java.net.URL(uploadLocation)
                                    "${baseUrl.protocol}://${baseUrl.host}$locationHeader"
                                }
                            }
                            Log.i("MessageSender", "TUS PATCH to: $patchUrl")

                            val patchReqBuilder = okhttp3.Request.Builder()
                                .url(patchUrl)
                                .addHeader("Tus-Resumable", "1.0.0")
                                .addHeader("Upload-Offset", "0")
                                .addHeader("Content-Type", "application/offset+octet-stream")
                                .patch(okhttp3.RequestBody.create(
                                    okhttp3.MediaType.parse("application/offset+octet-stream"),
                                    encrypted.ciphertext
                                ))

                            // Add Authorization header if available
                            if (headersObj != null && headersObj.has("Authorization")) {
                                patchReqBuilder.addHeader("Authorization", headersObj.getString("Authorization"))
                            }

                            val patchResp = okHttpClient.newCall(patchReqBuilder.build()).execute()
                            val patchCode = patchResp.code()
                            val patchBody = patchResp.body()?.string() ?: ""
                            val uploadOffset = patchResp.header("Upload-Offset")
                            Log.i("MessageSender", "TUS PATCH response: $patchCode, Upload-Offset: $uploadOffset, body: $patchBody")
                            uploadSucceeded = patchCode == 204 || patchResp.isSuccessful
                        } else {
                            Log.w("MessageSender", "TUS create failed with code $createCode, trying direct upload")
                            // Fallback: Direct PUT with all headers
                            val putReqBuilder = okhttp3.Request.Builder()
                                .url(uploadLocation)
                                .put(okhttp3.RequestBody.create(
                                    okhttp3.MediaType.parse("application/octet-stream"),
                                    encrypted.ciphertext
                                ))
                            if (headersObj != null) {
                                val hKeys = headersObj.keys()
                                while (hKeys.hasNext()) {
                                    val hk = hKeys.next()
                                    putReqBuilder.addHeader(hk, headersObj.getString(hk))
                                }
                            }
                            val putResp = okHttpClient.newCall(putReqBuilder.build()).execute()
                            Log.i("MessageSender", "PUT fallback response: ${putResp.code()}")
                            uploadSucceeded = putResp.isSuccessful
                        }
                    } else {
                        // Fallback legacy AWS S3 multipart upload
                        val uploadUrl = when {
                            formJson.has("url") -> formJson.getString("url")
                            formJson.has("location") -> formJson.getString("location")
                            formJson.has("action") -> formJson.getString("action")
                            else -> "https://signal-attachments.s3.amazonaws.com"
                        }

                        val builder = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                        val keys = formJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (key != "url" && key != "location" && key != "action" && key != "cdnNumber" && key != "cdn" && key != "uploadTimestamp") {
                                val value = formJson.getString(key)
                                builder.addFormDataPart(key, value)
                            }
                        }

                        builder.addFormDataPart(
                            "file",
                            "attachment.bin",
                            okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/octet-stream"), encrypted.ciphertext)
                        )

                        val uploadReq = okhttp3.Request.Builder().url(uploadUrl).post(builder.build()).build()
                        val uploadResp = okHttpClient.newCall(uploadReq).execute()
                        val respCode = uploadResp.code()
                        val respBody = uploadResp.body()?.string() ?: ""
                        Log.i("MessageSender", "Attachment upload result code: $respCode, body: $respBody")
                        uploadSucceeded = uploadResp.isSuccessful || respCode in 200..299
                    }
                } else {
                    Log.w("MessageSender", "Could not get upload form: ${formResp.code()} ${formResp.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                Log.e("MessageSender", "Error requesting/performing attachment upload", e)
            }

            if (!uploadSucceeded) {
                Log.e("MessageSender", "Attachment upload failed; aborting message send")
                return@withContext false
            }

            // Step 2: Build AttachmentPointer
            // encrypted.key is now 64 bytes (AES key + MAC key) which is what Signal expects
            Log.i("MessageSender", "Building AttachmentPointer: cdnKey=$cdnKey, cdnNumber=$cdnNumber, keySize=${encrypted.key.size}, digestSize=${encrypted.digest.size}, plaintextSize=${imageBytes.size}")
            val pointer = com.simonproyt.legacysignal.api.push.SignalServiceProtos.AttachmentPointer.newBuilder()
                .setCdnKey(cdnKey)
                .setCdnNumber(cdnNumber)
                .setKey(com.google.protobuf.ByteString.copyFrom(encrypted.key))
                .setDigest(com.google.protobuf.ByteString.copyFrom(encrypted.digest))
                .setContentType("image/jpeg")
                .setFileName("image.jpg")
                .setSize(imageBytes.size)
                .setUploadTimestamp(uploadTimestamp)
                .build()

            // Step 3: Build DataMessage and send
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val fetchPrekeys = attempt > 1 || !store.containsSession(SignalProtocolAddress(destinationUuid, 1))
                val preKeyResponse = if (fetchPrekeys) {
                    val response = api.getPreKeys(authHeader, destinationUuid).execute()
                    if (!response.isSuccessful || response.body() == null) {
                        return@withContext false
                    }
                    response.body()!!
                } else null

                val activeDeviceIds = if (preKeyResponse != null) {
                    preKeyResponse.devices.map { it.deviceId }
                } else {
                    val sessions = store.getSubDeviceSessions(destinationUuid)
                    if (sessions.isEmpty()) listOf(1) else sessions
                }

                val pushMessages = mutableListOf<OutgoingPushMessage>()
                var certBytes = store.getDeliveryCertificate()
                if (certBytes == null) {
                    val certResponse = api.getDeliveryCertificate(authHeader).execute()
                    if (certResponse.isSuccessful) {
                        val certB64 = certResponse.body()?.certificate
                        if (certB64 != null) {
                            certBytes = Base64.decode(certB64, Base64.NO_WRAP)
                            store.saveDeliveryCertificate(certBytes!!)
                        }
                    }
                }

                val senderCertificate = certBytes?.let {
                    try { SenderCertificate(it) } catch (e: Exception) { null }
                }

                for (deviceId in activeDeviceIds) {
                    val address = SignalProtocolAddress(destinationUuid, deviceId)
                    if (!store.containsSession(address)) {
                        if (preKeyResponse == null) continue
                        val device = preKeyResponse.devices.firstOrNull { it.deviceId == deviceId } ?: continue

                        val identityKey = IdentityKey(Base64.decode(preKeyResponse.identityKey, Base64.NO_WRAP))
                        val signedPreKeyPubKey = ECPublicKey(Base64.decode(device.signedPreKey?.publicKey, Base64.NO_WRAP))
                        val signedPreKeySig = Base64.decode(device.signedPreKey?.signature, Base64.NO_WRAP)
                        val preKeyPubKey = device.preKey?.publicKey?.let { ECPublicKey(Base64.decode(it, Base64.NO_WRAP)) }
                        val preKeyId = device.preKey?.keyId ?: 16777215
                        val signedPreKeyId = device.signedPreKey?.keyId ?: 0

                        val kyberPreKey = device.pqPreKey ?: throw IllegalStateException("No kyber prekey for device")
                        val kyberBytes = Base64.decode(kyberPreKey.publicKey, Base64.NO_WRAP)
                        val kyberKey = KEMPublicKey(kyberBytes, 0, kyberBytes.size)
                        val kyberSig = Base64.decode(kyberPreKey.signature, Base64.NO_WRAP)

                        val bundle = PreKeyBundle(
                            device.registrationId,
                            device.deviceId,
                            preKeyId,
                            preKeyPubKey,
                            signedPreKeyId,
                            signedPreKeyPubKey,
                            signedPreKeySig,
                            identityKey,
                            kyberPreKey.keyId ?: 0,
                            kyberKey,
                            kyberSig
                        )
                        val builder = SessionBuilder(store, address)
                        builder.process(bundle)
                    }

                    val dataMessageBuilder = com.simonproyt.legacysignal.api.push.SignalServiceProtos.DataMessage.newBuilder()
                        .setTimestamp(timestamp)
                        .addAttachments(pointer)

                    if (caption.isNotBlank()) {
                        dataMessageBuilder.setBody(caption)
                    }

                    val profileKeyBytes = com.simonproyt.legacysignal.CredentialsManager.getProfileKey(store.context)
                    if (profileKeyBytes != null) {
                        dataMessageBuilder.setProfileKey(com.google.protobuf.ByteString.copyFrom(profileKeyBytes))
                    }

                    val content = com.simonproyt.legacysignal.api.push.SignalServiceProtos.Content.newBuilder()
                        .setDataMessage(dataMessageBuilder.build())
                        .build()

                    val contentBytes = content.toByteArray()
                    val paddedBytes = ByteArray(contentBytes.size + 1)
                    System.arraycopy(contentBytes, 0, paddedBytes, 0, contentBytes.size)
                    paddedBytes[contentBytes.size] = 0x80.toByte()

                    var msgType = 1
                    val ciphertextMsgBytes = if (senderCertificate != null) {
                        val localUuid = UUID.fromString(senderCertificate.senderUuid)
                        val localE164 = senderCertificate.senderE164.orElse(null)
                        val localDeviceId = senderCertificate.senderDeviceId
                        val cipher = SealedSessionCipher(store, localUuid, localE164, localDeviceId)
                        msgType = 6
                        cipher.encrypt(address, senderCertificate, paddedBytes)
                    } else {
                        val cipher = SessionCipher(store, address)
                        val ciphertextMsg = cipher.encrypt(paddedBytes)
                        msgType = if (ciphertextMsg.type == CiphertextMessage.PREKEY_TYPE) 3 else 1
                        ciphertextMsg.serialize()
                    }

                    val remoteRegistrationId = try {
                        store.loadSession(address).remoteRegistrationId
                    } catch (e: Exception) { 0 }

                    pushMessages.add(
                        OutgoingPushMessage(
                            type = msgType,
                            destinationDeviceId = deviceId,
                            destinationRegistrationId = remoteRegistrationId,
                            content = Base64.encodeToString(ciphertextMsgBytes, Base64.NO_WRAP)
                        )
                    )
                }

                if (pushMessages.isEmpty()) {
                    return@withContext false
                }

                val pushList = OutgoingPushMessageList(
                    destination = destinationUuid,
                    timestamp = timestamp,
                    messages = pushMessages,
                    online = false,
                    urgent = true
                )

                val response = api.sendMessage(authHeader, destinationUuid, false, pushList).execute()
                if (response.isSuccessful) {
                    Log.d("MessageSender", "Image message sent successfully to $destinationUuid")
                    return@withContext true
                } else if (response.code() == 410 || response.code() == 409) {
                    for (msg in pushMessages) {
                        store.deleteSession(SignalProtocolAddress(destinationUuid, msg.destinationDeviceId))
                    }
                    continue
                } else {
                    Log.e("MessageSender", "Failed to send image message: ${response.code()}")
                    return@withContext false
                }
            }
            return@withContext false
        } catch (e: Exception) {
            Log.e("MessageSender", "Exception sending image message", e)
            return@withContext false
        }
    }
}
