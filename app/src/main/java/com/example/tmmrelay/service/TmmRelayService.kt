package com.example.tmmrelay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.tmmrelay.R
import com.example.tmmrelay.model.TelemetryPayload
import com.example.tmmrelay.util.DeviceInfoUtil
import java.time.Instant
import java.util.concurrent.TimeUnit

class TmmRelayService : Service() {

    private lateinit var wsClient: TmmWebSocketClient
    private val tenantId = "ASSAM_LAND_REGISTRY"
    private val apiKey: String? = null // replace with secure injection
    private var lastMessageAt: Instant = Instant.now()
    private val handler = Handler(Looper.getMainLooper())

    private val offlineCheck = object : Runnable {
        override fun run() {
            val minutes = java.time.Duration.between(lastMessageAt, Instant.now()).toMinutes()
            if (minutes >= 10) {
                emitOffline()
            }
            handler.postDelayed(this, TimeUnit.MINUTES.toMillis(1))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val deviceId = DeviceInfoUtil.deviceId(this)

        wsClient = TmmWebSocketClient(
            context = this,
            onMessage = { payload ->
                lastMessageAt = Instant.now()
                ApiClient.send(payload.copy(deviceId = deviceId), apiKey)
            },
            onError = { /* consider logging/retry strategy */ }
        )

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Connecting..."))
        wsClient.connect(tenantId, deviceId)
        handler.postDelayed(offlineCheck, TimeUnit.MINUTES.toMillis(1))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        wsClient.close()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GNSS Tracking Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tracker)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "TMM Relay",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun emitOffline() {
        val payload = TelemetryPayload(
            tenantId = tenantId,
            deviceId = DeviceInfoUtil.deviceId(this),
            latitude = 0.0,
            longitude = 0.0,
            battery = DeviceInfoUtil.batteryLevel(this),
            fixType = "UNKNOWN",
            timestamp = Instant.now().toString(),
            health = "OFFLINE"
        )
        ApiClient.send(payload, apiKey)
    }

    companion object {
        private const val CHANNEL_ID = "tmm_channel"
        private const val NOTIFICATION_ID = 1
    }
}
