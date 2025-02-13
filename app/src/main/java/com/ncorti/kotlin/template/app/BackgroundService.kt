package com.ncorti.kotlin.template.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackgroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "BackgroundServiceChannel"
        private const val TAG = "BackgroundService"
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_MEDIA_MOUNTED) {
                val usbPath = intent.data!!.path
                usbPath?.let {
                    val usbName = getUsbName(it)
                    copyFilesFromUsb(it, usbName)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Background USB Copy")
            .setContentText("Service is running in the background")
            .setSmallIcon(R.drawable.ic_notification)
            .build()
        startForeground(1, notification)

        val filter = IntentFilter(Intent.ACTION_MEDIA_MOUNTED)
        filter.addDataScheme("file")
        registerReceiver(usbReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Background Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun getUsbName(usbPath: String): String {
        val parts = usbPath.split("/")
        return parts.lastOrNull() ?: "UnknownUSB"
    }

    private fun copyFilesFromUsb(usbPath: String, usbName: String) {
        val usbDirectory = File(usbPath)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val destinationDirectory = File(Environment.getExternalStorageDirectory(), "UsbBackup/$usbName_$timestamp")
        if (!destinationDirectory.exists()) {
            destinationDirectory.mkdirs()
        }

        usbDirectory.listFiles()?.forEach { file ->
            if (file.isFile) {
                try {
                    val destFile = File(destinationDirectory, file.name)
                    copyFile(file, destFile)
                } catch (e: IOException) {
                    Log.e(TAG, "Error copying file: ${file.name}", e)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                val buffer = ByteArray(1024)
                var length: Int
                while (input.read(buffer).also { length = it } > 0) {
                    output.write(buffer, 0, length)
                }
            }
        }
    }
}
