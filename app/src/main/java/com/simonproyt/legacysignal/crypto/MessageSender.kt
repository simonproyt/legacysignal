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
                    val content = com.simonproyt.legacysignal.api.push.SignalServiceProtos.Content.newBuilder()
                        .setDataMessage(
                            com.simonproyt.legacysignal.api.push.SignalServiceProtos.DataMessage.newBuilder()
                                .setTimestamp(timestamp)
                                .setBody(messageBody)
                                .build()
                        )
                        .build()

                    val contentBytes = content.toByteArray()
                    val paddedBytes = ByteArray(contentBytes.size + 1)
                    System.arraycopy(contentBytes, 0, paddedBytes, 0, contentBytes.size)
                    paddedBytes[contentBytes.size] = 0x80.toByte()

                    val cipher = SessionCipher(store, address)
                    val ciphertextMsg = cipher.encrypt(paddedBytes)
                    val msgType = if (ciphertextMsg.type == CiphertextMessage.PREKEY_TYPE) 3 else 1
                    
                    val remoteRegistrationId = try {
                        store.loadSession(address).remoteRegistrationId
                    } catch (e: Exception) {
                        0
                    }

                    pushMessages.add(OutgoingPushMessage(
                        type = msgType,
                        destinationDeviceId = deviceId,
                        destinationRegistrationId = remoteRegistrationId,
                        content = Base64.encodeToString(ciphertextMsg.serialize(), Base64.NO_WRAP)
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
}
