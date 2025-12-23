package com.hirenq.tmmrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hirenq.tmmrelay.service.TmmRelayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val serviceIntent = Intent(context, TmmRelayService::class.java)
            // minSdk is 29 (Android 10), so startForegroundService is always available
            context.startForegroundService(serviceIntent)
        }
    }
}
