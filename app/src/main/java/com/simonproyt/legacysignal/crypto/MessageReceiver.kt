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
    
    fun decryptMessage(envelope: SignalServiceProtos.Envelope): String {
        try {
            val senderId = getSourceUuid(envelope)
            if (senderId == null) {
                return "[Missing Source ID]"
            }

            val deviceId = if (envelope.hasSourceDeviceId()) envelope.sourceDeviceId else 1
            val address = SignalProtocolAddress(senderId, deviceId)
            val cipher = SessionCipher(store, address)

            val plaintextBytes = when (envelope.type) {
                SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE -> {
                    val message = PreKeySignalMessage(envelope.content.toByteArray())
                    cipher.decrypt(message)
                }
                SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET -> {
                    val message = SignalMessage(envelope.content.toByteArray())
                    cipher.decrypt(message)
                }
                else -> {
                    Log.w("MessageReceiver", "Unsupported message type: ${envelope.type}")
                    return "[Unsupported Message Type]"
                }
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

            if (content?.hasDataMessage() == true) {
                return content.dataMessage.body
            }
            
            return "[No Data Message]"
        } catch (e: Exception) {
            Log.e("MessageReceiver", "Failed to decrypt message", e)
            return "[Decryption Failed]"
        }
    }
}
