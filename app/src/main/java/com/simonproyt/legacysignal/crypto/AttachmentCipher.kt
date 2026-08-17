package com.simonproyt.legacysignal.crypto

import android.util.Log
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedAttachment(
    val key: ByteArray,
    val ciphertext: ByteArray,
    val digest: ByteArray
)

object AttachmentCipher {
    private const val TAG = "AttachmentCipher"

    fun encrypt(rawBytes: ByteArray): EncryptedAttachment {
        val random = java.security.SecureRandom()

        // Generate combined 64-byte key directly: first 32 = AES key, last 32 = MAC key
        val combinedKey = ByteArray(64).also { random.nextBytes(it) }
        val aesKey = combinedKey.copyOfRange(0, 32)
        val macKey = combinedKey.copyOfRange(32, 64)
        val iv = ByteArray(16).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(rawBytes)

        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(macKey, "HmacSHA256"))
        hmac.update(iv)
        hmac.update(encrypted)
        val mac = hmac.doFinal()

        // Combined ciphertext: IV (16) + AES-CBC ciphertext + HMAC (32)
        val combinedCiphertext = ByteArray(16 + encrypted.size + 32)
        System.arraycopy(iv, 0, combinedCiphertext, 0, 16)
        System.arraycopy(encrypted, 0, combinedCiphertext, 16, encrypted.size)
        System.arraycopy(mac, 0, combinedCiphertext, 16 + encrypted.size, 32)

        // Digest is SHA-256 of the entire combined ciphertext (IV + encrypted + MAC)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(combinedCiphertext)

        return EncryptedAttachment(
            key = combinedKey,  // 64-byte key: AES (32) + MAC (32)
            ciphertext = combinedCiphertext,
            digest = digest
        )
    }

    fun decrypt(encryptedBytes: ByteArray, keyBytes: ByteArray): ByteArray {
        if (encryptedBytes.isEmpty()) {
            throw IllegalArgumentException("Empty encrypted bytes")
        }

        // Strategy 1: Direct 64-byte key
        if (keyBytes.size == 64) {
            try {
                return decryptWith64ByteKey(encryptedBytes, keyBytes)
            } catch (e: Exception) {
                Log.w(TAG, "Direct 64-byte key decrypt failed: ${e.message}")
            }
        }

        // Strategy 2: 32-byte key expanded via HKDF
        if (keyBytes.size == 32) {
            val infos = listOf(
                "Attachment Keys".toByteArray(),
                "Signal Attachment".toByteArray(),
                "WhisperAttachment".toByteArray(),
                ByteArray(0)
            )
            for (info in infos) {
                try {
                    val derived64 = org.signal.libsignal.protocol.kdf.HKDF.deriveSecrets(keyBytes, info, 64)
                    return decryptWith64ByteKey(encryptedBytes, derived64)
                } catch (e: Exception) {
                    // Try next
                }
            }

            // Strategy 3: AES-GCM (12-byte IV)
            if (encryptedBytes.size > 28) {
                try {
                    val iv = encryptedBytes.copyOfRange(0, 12)
                    val cipherData = encryptedBytes.copyOfRange(12, encryptedBytes.size)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
                    return cipher.doFinal(cipherData)
                } catch (e: Exception) {
                    Log.w(TAG, "AES-GCM decrypt failed: ${e.message}")
                }
            }

            // Strategy 4: AES-CTR (16-byte IV)
            if (encryptedBytes.size > 16) {
                try {
                    val iv = encryptedBytes.copyOfRange(0, 16)
                    val cipherData = encryptedBytes.copyOfRange(16, encryptedBytes.size)
                    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
                    return cipher.doFinal(cipherData)
                } catch (e: Exception) {
                    Log.w(TAG, "AES-CTR decrypt failed: ${e.message}")
                }
            }

            // Strategy 5: Direct AES-CBC (16-byte IV)
            if (encryptedBytes.size > 16) {
                try {
                    val iv = encryptedBytes.copyOfRange(0, 16)
                    val cipherData = encryptedBytes.copyOfRange(16, encryptedBytes.size)
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
                    return cipher.doFinal(cipherData)
                } catch (e: Exception) {
                    Log.w(TAG, "AES-CBC 32-byte decrypt failed: ${e.message}")
                }
            }
        }

        // Strategy 6: Fallback generic AES-CBC
        val key = if (keyBytes.size >= 32) keyBytes.copyOfRange(0, 32) else keyBytes
        val iv = encryptedBytes.copyOfRange(0, 16)
        val data = encryptedBytes.copyOfRange(16, encryptedBytes.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun decryptWith64ByteKey(encryptedBytes: ByteArray, keyBytes: ByteArray): ByteArray {
        val aesKey = keyBytes.copyOfRange(0, 32)
        val macKey = keyBytes.copyOfRange(32, 64)

        if (encryptedBytes.size > 48) {
            val iv = encryptedBytes.copyOfRange(0, 16)
            val ciphertextLen = encryptedBytes.size - 16 - 32
            val ciphertext = encryptedBytes.copyOfRange(16, 16 + ciphertextLen)
            val expectedMac = encryptedBytes.copyOfRange(encryptedBytes.size - 32, encryptedBytes.size)

            try {
                val hmac = Mac.getInstance("HmacSHA256")
                hmac.init(SecretKeySpec(macKey, "HmacSHA256"))
                hmac.update(iv)
                hmac.update(ciphertext)
                val computedMac = hmac.doFinal()

                if (!Arrays.equals(expectedMac, computedMac)) {
                    Log.w(TAG, "HMAC mismatch on attachment, attempting decryption anyway")
                }

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
                return cipher.doFinal(ciphertext)
            } catch (e: Exception) {
                Log.w(TAG, "AES-CBC with MAC strip failed: ${e.message}, trying raw ciphertext")
            }
        }

        // Try without trailing MAC
        if (encryptedBytes.size > 16) {
            val iv = encryptedBytes.copyOfRange(0, 16)
            val ciphertext = encryptedBytes.copyOfRange(16, encryptedBytes.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(ciphertext)
        }

        throw IllegalArgumentException("Ciphertext too short for 64-byte key decryption")
    }
}
