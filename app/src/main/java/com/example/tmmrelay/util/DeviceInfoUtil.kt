package com.example.tmmrelay.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import java.time.Instant
import java.time.Duration

object DeviceInfoUtil {

    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"

    fun batteryLevel(context: Context): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        return (level * 100) / scale
    }

    fun health(battery: Int, fixType: String, lastMessageAt: Instant?): String {
        if (battery < 20) return "LOW_BATTERY"
        if (fixType.equals("NO_FIX", ignoreCase = true)) return "NO_SIGNAL"
        lastMessageAt?.let {
            val minutes = Duration.between(it, Instant.now()).toMinutes()
            if (minutes >= 10) return "OFFLINE"
        }
        return "OK"
    }
}
