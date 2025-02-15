package com.UsbManger.rkgroup.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d("BootReceiver", "Boot completed detected")
                val serviceIntent = Intent(context, BackgroundService::class.java)
                context.startService(serviceIntent)
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.d("BootReceiver", "USB device attached detected")
                val serviceIntent = Intent(context, BackgroundService::class.java)
                context.startService(serviceIntent)
            }
        }
    }
}