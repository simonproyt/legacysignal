package com.simonproyt.legacysignal

import android.content.Context
import android.content.SharedPreferences

object CredentialsManager {
    private const val PREFS_NAME = "LegacySignalPrefs"
    private const val KEY_PHONE_NUMBER = "PHONE_NUMBER"
    private const val KEY_PASSWORD = "PASSWORD"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveCredentials(context: Context, phoneNumber: String, password: String) {
        getPrefs(context).edit()
            .putString(KEY_PHONE_NUMBER, phoneNumber)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun getPhoneNumber(context: Context): String? {
        return getPrefs(context).getString(KEY_PHONE_NUMBER, null)
    }

    fun getPassword(context: Context): String? {
        return getPrefs(context).getString(KEY_PASSWORD, null)
    }

    fun hasCredentials(context: Context): Boolean {
        return getPhoneNumber(context) != null && getPassword(context) != null
    }

    fun clearCredentials(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
