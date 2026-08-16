package com.simonproyt.legacysignal

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDexApplication

class LegacySignalApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("SignalPrefs", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(themeMode)
    }
}
