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
        BackgroundSyncManager.start(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
