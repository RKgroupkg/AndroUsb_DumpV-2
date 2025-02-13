package com.ncorti.kotlin.template.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action.equals(Intent.ACTION_BOOT_COMPLETED)) {
            val serviceIntent = Intent(context, BackgroundService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
