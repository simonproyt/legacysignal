package com.simonproyt.legacysignal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Device booted, starting SyncService")
            
            // Only start if they are actually logged in (have phone number)
            val phone = CredentialsManager.getPhoneNumber(context)
            if (!phone.isNullOrEmpty()) {
                val serviceIntent = Intent(context, SyncService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
