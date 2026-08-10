package com.simonproyt.legacysignal.api.crypto

import org.signal.libsignal.zkgroup.profiles.ProfileKey
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

class ProfileCipher(private val key: ProfileKey) {
    fun encrypt(input: ByteArray, paddedLength: Int): ByteArray {
        val inputPadded = ByteArray(paddedLength)
        System.arraycopy(input, 0, inputPadded, 0, input.size)
        
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        
        val cipher = GCMBlockCipher(AESEngine())
        val params = AEADParameters(KeyParameter(key.serialize()), 128, nonce)
        cipher.init(true, params)
        
        val encrypted = ByteArray(cipher.getOutputSize(inputPadded.size))
        val len = cipher.processBytes(inputPadded, 0, inputPadded.size, encrypted, 0)
        cipher.doFinal(encrypted, len)
        
        val result = ByteArray(nonce.size + encrypted.size)
        System.arraycopy(nonce, 0, result, 0, nonce.size)
        System.arraycopy(encrypted, 0, result, nonce.size, encrypted.size)
        return result
    }
    
    fun encryptString(input: String, paddedLength: Int): ByteArray {
        return encrypt(input.toByteArray(StandardCharsets.UTF_8), paddedLength)
    }
    
    fun encryptBoolean(input: Boolean): ByteArray {
        val value = ByteArray(1)
        value[0] = if (input) 1.toByte() else 0.toByte()
        return encrypt(value, 1)
    }

    companion object {
        fun deriveUnidentifiedAccessKey(profileKey: ByteArray): ByteArray {
            val nonce = ByteArray(12)
            val input = ByteArray(16)
            
            val cipher = GCMBlockCipher(AESEngine())
            val params = AEADParameters(KeyParameter(profileKey), 128, nonce)
            cipher.init(true, params)
            
            val encrypted = ByteArray(cipher.getOutputSize(input.size))
            val len = cipher.processBytes(input, 0, input.size, encrypted, 0)
            cipher.doFinal(encrypted, len)
            
            val result = ByteArray(16)
            System.arraycopy(encrypted, 0, result, 0, 16)
            return result
        }
    }
}
