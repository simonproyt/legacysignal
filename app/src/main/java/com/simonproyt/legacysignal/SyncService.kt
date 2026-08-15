package com.simonproyt.legacysignal

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SyncService : Service() {

    override fun onCreate() {
        super.onCreate()
        
        // Start foreground to keep the service alive
        val notificationIntent = Intent(this, HomeActivity::class.java)
        
        // Use FLAG_UPDATE_CURRENT for older API compatibility, FLAG_IMMUTABLE is API 23+
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags)
        
        val notification = NotificationCompat.Builder(this, "legacy_signal_service_channel")
            .setContentTitle("Signal")
            .setContentText("Running in background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
            
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("SignalPrefs", android.content.Context.MODE_PRIVATE)
        val intervalMins = prefs.getInt("sync_interval_mins", 0)

        BackgroundSyncManager.start(this)
        
        if (intervalMins > 0) {
            // Polling mode: wait 10 seconds for messages to arrive, then stop
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                BackgroundSyncManager.stop()
                
                // Schedule next poll
                val alarmManager = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                val receiverIntent = Intent(this, SyncReceiver::class.java)
                val pFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                } else {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = android.app.PendingIntent.getBroadcast(this, 0, receiverIntent, pFlags)
                
                val triggerTime = System.currentTimeMillis() + (intervalMins * 60 * 1000L)
                alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                
                // Stop service (removes foreground notification and saves battery)
                stopSelf()
            }, 10000) // 10 seconds
        } else {
            // Persistent mode: cancel any existing alarms
            val alarmManager = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val receiverIntent = Intent(this, SyncReceiver::class.java)
            val pFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(this, 0, receiverIntent, pFlags)
            alarmManager.cancel(pendingIntent)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
