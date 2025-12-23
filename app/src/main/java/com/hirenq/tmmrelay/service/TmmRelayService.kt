package com.hirenq.tmmrelay.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hirenq.tmmrelay.R
import com.hirenq.tmmrelay.model.TelemetryPayload
import com.hirenq.tmmrelay.util.DeviceInfoUtil
import java.time.Instant
import java.util.concurrent.TimeUnit

class TmmRelayService : Service() {

    private lateinit var wsClient: TmmWebSocketClient
    private val tenantId = "ASSAM_LAND_REGISTRY"
    private val apiKey: String? = null

    private var lastMessageAt: Instant = Instant.now()
    private var lastSuccessfulPostAt: Instant? = null
    private var isRelayStarted = false

    private var lastPostTimestamp: String? = null
    private var lastPostPayload: String? = null

    private var lastKnownLatitude = 0.0
    private var lastKnownLongitude = 0.0
    private var lastKnownFixType = "UNKNOWN"

    private val handler = Handler(Looper.getMainLooper())

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // -------------------- PERMISSION DIAGNOSTICS --------------------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        else true

    // -------------------- DIAGNOSTICS BROADCAST --------------------

    private fun broadcastDiagnostics(payload: TelemetryPayload) {
        val intent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
            putExtra("locationPermission", hasLocationPermission())
            putExtra("bluetoothPermission", hasBluetoothPermission())

            putExtra("fixType", payload.fixType)
            putExtra("satellites", payload.satellites)
            putExtra("horizontalAccuracy", payload.horizontalAccuracy)
            putExtra("verticalAccuracy", payload.verticalAccuracy)
            putExtra("receiverHealth", payload.receiverHealth ?: "UNKNOWN")

            payload.receiverBattery?.let {
                putExtra("receiverBattery", it)
            }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // -------------------- RUNNABLES --------------------

    private val offlineCheck = object : Runnable {
        override fun run() {
            val minutes =
                java.time.Duration.between(lastMessageAt, Instant.now()).toMinutes()
            if (minutes >= 10) emitOffline()
            handler.postDelayed(this, TimeUnit.MINUTES.toMillis(1))
        }
    }

    private val periodicPostCheck = object : Runnable {
        override fun run() {
            if (isRelayStarted && (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0)) {
                sendPeriodicPost()
            }
            handler.postDelayed(this, TimeUnit.MINUTES.toMillis(5))
        }
    }

    private val statusUpdateCheck = object : Runnable {
        override fun run() {
            if (isRelayStarted) updateDynamicStatus()
            handler.postDelayed(this, TimeUnit.SECONDS.toMillis(30))
        }
    }

    private val diagnosticsUpdateCheck = object : Runnable {
        override fun run() {
            if (isRelayStarted) {
                // Broadcast diagnostics periodically even if no messages received
                val payload = TelemetryPayload(
                    tenantId = tenantId,
                    deviceId = DeviceInfoUtil.deviceId(this@TmmRelayService),
                    latitude = lastKnownLatitude,
                    longitude = lastKnownLongitude,
                    battery = DeviceInfoUtil.batteryLevel(this@TmmRelayService),
                    fixType = lastKnownFixType,
                    timestamp = Instant.now().toString(),
                    health = "OK",
                    horizontalAccuracy = -1.0,
                    verticalAccuracy = -1.0,
                    satellites = -1
                )
                broadcastDiagnostics(payload)
            }
            handler.postDelayed(this, TimeUnit.SECONDS.toMillis(10))
        }
    }

    // -------------------- SERVICE LIFECYCLE --------------------

    override fun onCreate() {
        super.onCreate()
        val deviceId = DeviceInfoUtil.deviceId(this)

        wsClient = TmmWebSocketClient(
            context = this,
            onMessage = { payload ->
                lastMessageAt = Instant.now()

                if (payload.latitude != 0.0 || payload.longitude != 0.0) {
                    lastKnownLatitude = payload.latitude
                    lastKnownLongitude = payload.longitude
                    lastKnownFixType = payload.fixType
                }

                broadcastDiagnostics(payload)

                val shouldSendPost =
                    lastSuccessfulPostAt == null ||
                        java.time.Duration
                            .between(lastSuccessfulPostAt, Instant.now())
                            .toMinutes() >= 5

                if (shouldSendPost) {
                    ApiClient.send(
                        payload.copy(deviceId = deviceId),
                        apiKey
                    ) { timestamp, payloadInfo, success ->
                        if (success) lastSuccessfulPostAt = Instant.now()
                        updateNotificationWithPost(timestamp, payloadInfo)
                        updateDynamicStatus()
                    }
                }
            },
            onError = {
                android.util.Log.e("TmmRelayService", "WebSocket error", it)
            }
        )

        createNotificationChannel()
        isRelayStarted = true

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Started")
        )

        wsClient.connect(tenantId, deviceId)

        // Send initial diagnostics broadcast
        val initialPayload = TelemetryPayload(
            tenantId = tenantId,
            deviceId = deviceId,
            latitude = 0.0,
            longitude = 0.0,
            battery = DeviceInfoUtil.batteryLevel(this),
            fixType = "UNKNOWN",
            timestamp = Instant.now().toString(),
            health = "OK",
            horizontalAccuracy = -1.0,
            verticalAccuracy = -1.0,
            satellites = -1
        )
        broadcastDiagnostics(initialPayload)

        handler.postDelayed(offlineCheck, TimeUnit.MINUTES.toMillis(1))
        handler.postDelayed(periodicPostCheck, TimeUnit.MINUTES.toMillis(5))
        handler.postDelayed(statusUpdateCheck, TimeUnit.SECONDS.toMillis(30))
        handler.postDelayed(diagnosticsUpdateCheck, TimeUnit.SECONDS.toMillis(10))
    }

    override fun onDestroy() {
        isRelayStarted = false
        wsClient.close()
        handler.removeCallbacksAndMessages(null)
        updateNotification("Stopped")
        broadcastStatusUpdate("Stopped", null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------- POSTS --------------------

    private fun emitOffline() {
        val payload = TelemetryPayload(
            tenantId = tenantId,
            deviceId = DeviceInfoUtil.deviceId(this),
            latitude = 0.0,
            longitude = 0.0,
            battery = DeviceInfoUtil.batteryLevel(this),
            fixType = "UNKNOWN",
            timestamp = Instant.now().toString(),
            health = "OFFLINE",
            horizontalAccuracy = -1.0,
            verticalAccuracy = -1.0,
            satellites = -1
        )
        broadcastDiagnostics(payload)
    }

    private fun sendPeriodicPost() {
        val payload = TelemetryPayload(
            tenantId = tenantId,
            deviceId = DeviceInfoUtil.deviceId(this),
            latitude = lastKnownLatitude,
            longitude = lastKnownLongitude,
            battery = DeviceInfoUtil.batteryLevel(this),
            fixType = lastKnownFixType,
            timestamp = Instant.now().toString(),
            health = "OK",
            horizontalAccuracy = -1.0,
            verticalAccuracy = -1.0,
            satellites = -1
        )
        broadcastDiagnostics(payload)
    }

    // -------------------- NOTIFICATION & STATUS --------------------

    private fun updateDynamicStatus() {
        val status =
            if (!isRelayStarted) "Stopped"
            else if (lastSuccessfulPostAt == null) "Started"
            else "Waiting for websocket of TMM"

        updateNotification(status)
        broadcastStatusUpdate(status, null)
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AltGeo TMM Relay")
            .setContentText("Status: $status")
            .setSmallIcon(R.drawable.ic_tracker)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(status: String) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(status)
        )
    }

    private fun updateNotificationWithPost(timestamp: String, payloadInfo: String) {
        lastPostTimestamp = timestamp
        lastPostPayload = payloadInfo
        updateNotification("Started")
    }

    private fun broadcastStatusUpdate(status: String, postInfo: String?) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_POST_TIMESTAMP, lastPostTimestamp ?: "")
            putExtra(EXTRA_POST_PAYLOAD, lastPostPayload ?: "")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AltGeo TMM Relay",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "tmm_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STATUS_UPDATE =
            "com.hirenq.tmmrelay.STATUS_UPDATE"

        const val ACTION_DIAGNOSTICS_UPDATE =
            "com.hirenq.tmmrelay.DIAGNOSTICS_UPDATE"

        const val EXTRA_STATUS = "status"
        const val EXTRA_POST_TIMESTAMP = "post_timestamp"
        const val EXTRA_POST_PAYLOAD = "post_payload"
    }
}
