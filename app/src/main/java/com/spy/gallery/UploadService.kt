package com.spy.gallery
import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import okhttp3.*

class UploadService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel("upload_channel", "Sync", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "upload_channel")
            .setContentTitle("Syncing media...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        startForeground(1, notification)
        
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}