package com.UsbManger.rkgroup.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "UsbServiceChannel"
        const val NOTIFICATION_ID = 1
        const val COMPLETION_ID = 2
    }

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val builder = NotificationCompat.Builder(context, CHANNEL_ID)

    init {
        createNotificationChannel()
    }

    fun createForegroundNotification() = builder
        .setContentTitle("USB Manager")
        .setContentText("Scanning device")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    fun showNotification(title: String, message: String, id: Int = NOTIFICATION_ID) {
        manager.notify(id, builder
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build())
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID,
                "USB Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows USB scanning status"
                manager.createNotificationChannel(this)
            }
        }
    }
}