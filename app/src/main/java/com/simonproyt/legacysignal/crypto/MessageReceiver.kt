package com.simonproyt.legacysignal.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import com.simonproyt.legacysignal.api.push.SignalServiceProtos
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.util.UUID
import java.nio.ByteBuffer

class MessageReceiver(private val context: Context) {

    private val store = SharedPrefsSignalProtocolStore(context)
    
    fun getSourceUuid(envelope: SignalServiceProtos.Envelope): String? {
        if (envelope.hasSourceServiceId()) {
            return envelope.sourceServiceId
        }
        if (envelope.hasSourceServiceIdBinary()) {
            val bytes = envelope.sourceServiceIdBinary.toByteArray()
            if (bytes.size == 16) {
                val bb = ByteBuffer.wrap(bytes)
                return UUID(bb.long, bb.long).toString()
            }
        }
        return null
    }
    
    data class DecryptedMessage(val senderId: String, val body: String, val profileKey: ByteArray? = null)

    fun decryptMessage(envelope: SignalServiceProtos.Envelope): DecryptedMessage {
        var senderId = getSourceUuid(envelope)
        val deviceId = if (envelope.hasSourceDeviceId()) envelope.sourceDeviceId else 1

        try {
            val plaintextBytes = when (envelope.type) {
                SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE -> {
                    if (senderId == null) return DecryptedMessage("Unknown", "[Missing Source ID]")
                    val address = SignalProtocolAddress(senderId, deviceId)
                    val cipher = SessionCipher(store, address)
                    val message = PreKeySignalMessage(envelope.content.toByteArray())
                    cipher.decrypt(message)
                }
                SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET -> {
                    if (senderId == null) return DecryptedMessage("Unknown", "[Missing Source ID]")
                    val address = SignalProtocolAddress(senderId, deviceId)
                    val cipher = SessionCipher(store, address)
                    val message = SignalMessage(envelope.content.toByteArray())
                    cipher.decrypt(message)
                }
                SignalServiceProtos.Envelope.Type.UNIDENTIFIED_SENDER -> {
                    val prefs = context.getSharedPreferences("SignalPrefs", Context.MODE_PRIVATE)
                    val localUuidStr = prefs.getString("uuid", null) ?: UUID.randomUUID().toString()
                    val localE164 = prefs.getString("phone_number", "")
                    
                    val validator = object : org.signal.libsignal.metadata.certificate.CertificateValidator(emptyList()) {
                        override fun validate(
                            certificate: org.signal.libsignal.metadata.certificate.SenderCertificate,
                            validationTime: Long
                        ) {
                            // Trust all for legacy compatibility
                        }
                    }
                    val sealedCipher = org.signal.libsignal.metadata.SealedSessionCipher(
                        store,
                        UUID.fromString(localUuidStr),
                        localE164,
                        1
                    )
                    
                    val result = sealedCipher.decrypt(validator, envelope.content.toByteArray(), System.currentTimeMillis())
                    senderId = result.senderUuid
                    result.paddedMessage
                }
                SignalServiceProtos.Envelope.Type.SERVER_DELIVERY_RECEIPT -> {
                    return DecryptedMessage(senderId ?: "Unknown", "[Receipt]")
                }
                else -> {
                    Log.w("MessageReceiver", "Unsupported message type: ${envelope.type}")
                    return DecryptedMessage(senderId ?: "Unknown", "[Unsupported Message Type]")
                }
            }

            if (senderId == null) {
                return DecryptedMessage("Unknown", "[Missing Source ID]")
            }

            // Strip trailing zero padding and optional 0x80 padding byte
            var unpaddedLen = plaintextBytes.size
            while (unpaddedLen > 0 && plaintextBytes[unpaddedLen - 1] == 0.toByte()) {
                unpaddedLen--
            }
            if (unpaddedLen > 0 && (plaintextBytes[unpaddedLen - 1].toInt() and 0xFF) == 0x80) {
                unpaddedLen--
            }

            var content: SignalServiceProtos.Content? = null
            try {
                content = SignalServiceProtos.Content.parseFrom(plaintextBytes.copyOfRange(0, unpaddedLen))
            } catch (e: Exception) {
                Log.w("MessageReceiver", "Failed to parse unpadded content, falling back to original bytes", e)
                content = SignalServiceProtos.Content.parseFrom(plaintextBytes)
            }

            var profileKey: ByteArray? = null
            if (content?.hasDataMessage() == true) {
                if (content.dataMessage.hasProfileKey()) {
                    val keyBytes = content.dataMessage.profileKey.toByteArray()
                    Log.i("MessageReceiver", "Parsed profile key from $senderId, size: ${keyBytes.size}")
                    if (keyBytes.size == 32) profileKey = keyBytes
                } else {
                    Log.i("MessageReceiver", "No profile key found in DataMessage from $senderId")
                }
                
                if (content.dataMessage.body.isNullOrEmpty()) {
                    return DecryptedMessage(senderId, "[No Body/Receipt]", profileKey)
                }
                return DecryptedMessage(senderId, content.dataMessage.body, profileKey)
            }
            
            return DecryptedMessage(senderId, "[No Data Message]", profileKey)
        } catch (e: Exception) {
            Log.e("MessageReceiver", "Failed to decrypt message", e)
            return DecryptedMessage(senderId ?: "Unknown", "[Decryption Failed]")
        }
    }
}
