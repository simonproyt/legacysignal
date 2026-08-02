package com.simonproyt.legacysignal

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher

object CredentialsManager {
    private const val PREFS_NAME = "LegacySignalPrefs"
    private const val KEY_PHONE_NUMBER = "PHONE_NUMBER"
    private const val KEY_PASSWORD = "PASSWORD"
    
    private const val ALIAS = "LegacySignalKey"
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private fun getOrCreateKey(context: Context) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (!keyStore.containsAlias(ALIAS)) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                Api23Impl.generateKey(ALIAS)
            } else {
                @Suppress("DEPRECATION")
                val start = java.util.Calendar.getInstance()
                val end = java.util.Calendar.getInstance()
                end.add(java.util.Calendar.YEAR, 30)
                
                @Suppress("DEPRECATION")
                val spec = android.security.KeyPairGeneratorSpec.Builder(context)
                    .setAlias(ALIAS)
                    .setSubject(javax.security.auth.x500.X500Principal("CN=$ALIAS"))
                    .setSerialNumber(java.math.BigInteger.TEN)
                    .setStartDate(start.time)
                    .setEndDate(end.time)
                    .build()
                val keyGenerator = java.security.KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
                keyGenerator.initialize(spec)
                keyGenerator.generateKeyPair()
            }
        }
    }
    
    private fun encrypt(context: Context, plainText: String): String {
        getOrCreateKey(context)
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val publicKey = keyStore.getCertificate(ALIAS).publicKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
    }

    private fun decrypt(context: Context, encryptedText: String): String {
        getOrCreateKey(context)
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val privateKey = keyStore.getKey(ALIAS, null) as java.security.PrivateKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val encryptedBytes = Base64.decode(encryptedText, Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun saveCredentials(context: Context, phoneNumber: String, password: String) {
        val encPhone = encrypt(context, phoneNumber)
        val encPassword = encrypt(context, password)
        getPrefs(context).edit()
            .putString(KEY_PHONE_NUMBER, encPhone)
            .putString(KEY_PASSWORD, encPassword)
            .apply()
    }

    fun saveSignalKeys(context: Context, aciIdentityStr: String, pniIdentityStr: String, aciKyberStr: String, pniKyberStr: String) {
        getPrefs(context).edit()
            .putString("ACI_IDENTITY", aciIdentityStr)
            .putString("PNI_IDENTITY", pniIdentityStr)
            .putString("ACI_KYBER", aciKyberStr)
            .putString("PNI_KYBER", pniKyberStr)
            .apply()
    }

    fun getPhoneNumber(context: Context): String? {
        val enc = getPrefs(context).getString(KEY_PHONE_NUMBER, null) ?: return null
        return try { decrypt(context, enc) } catch (e: Exception) { null }
    }

    fun getPassword(context: Context): String? {
        val enc = getPrefs(context).getString(KEY_PASSWORD, null) ?: return null
        return try { decrypt(context, enc) } catch (e: Exception) { null }
    }

    fun hasCredentials(context: Context): Boolean {
        return getPrefs(context).getString(KEY_PHONE_NUMBER, null) != null && 
               getPrefs(context).getString(KEY_PASSWORD, null) != null
    }

    fun clearCredentials(context: Context) {
        getPrefs(context).edit().clear().apply()
}

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.M)
    private object Api23Impl {
        fun generateKey(alias: String) {
            val keyGenerator = java.security.KeyPairGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_RSA,
                "AndroidKeyStore"
            )
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .build()
            keyGenerator.initialize(spec)
            keyGenerator.generateKeyPair()
        }
    }
}
