package com.hirenq.tmmrelay.service

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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hirenq.tmmrelay.R
import com.hirenq.tmmrelay.model.TelemetryPayload
import com.hirenq.tmmrelay.util.DeviceInfoUtil
import java.time.Instant
import java.util.concurrent.TimeUnit

class TmmRelayService : Service() {

    private lateinit var wsClient: TmmWebSocketClient
    private val tenantId = "ASSAM_LAND_REGISTRY"
    private val apiKey: String? = null // replace with secure injection
    private var lastMessageAt: Instant = Instant.now()
    private val handler = Handler(Looper.getMainLooper())
    private var isRelayStarted = false
    private var lastPostTimestamp: String? = null
    private var lastPostPayload: String? = null
    private var lastKnownLatitude: Double = 0.0
    private var lastKnownLongitude: Double = 0.0
    private var lastKnownFixType: String = "UNKNOWN"
    private var lastSuccessfulPostAt: Instant? = null
    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val offlineCheck = object : Runnable {
        override fun run() {
            val minutes = java.time.Duration.between(lastMessageAt, Instant.now()).toMinutes()
            if (minutes >= 10) {
                emitOffline()
            }
            handler.postDelayed(this, TimeUnit.MINUTES.toMillis(1))
        }
    }

    // Periodic POST call every 5 minutes
    private val periodicPostCheck = object : Runnable {
        override fun run() {
            if (isRelayStarted) {
                // Only send periodic POST if we have valid coordinates (not 0,0)
                if (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0) {
                    sendPeriodicPost()
                } else {
                    android.util.Log.d("TmmRelayService", "Skipping periodic POST - no valid coordinates yet")
                }
            }
            handler.postDelayed(this, TimeUnit.MINUTES.toMillis(5))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val deviceId = DeviceInfoUtil.deviceId(this)

        wsClient = TmmWebSocketClient(
            context = this,
            onMessage = { payload ->
                lastMessageAt = Instant.now()
                // Store last known location for periodic POSTs (only if valid)
                if (payload.latitude != 0.0 || payload.longitude != 0.0) {
                    lastKnownLatitude = payload.latitude
                    lastKnownLongitude = payload.longitude
                    lastKnownFixType = payload.fixType
                    android.util.Log.d("TmmRelayService", "Updated last known location: Lat=${payload.latitude}, Lng=${payload.longitude}")
                }
                
                // Check if 5 minutes have passed since last successful POST
                val shouldSendPost = if (lastSuccessfulPostAt != null) {
                    val minutesSinceLastPost = java.time.Duration.between(lastSuccessfulPostAt, Instant.now()).toMinutes()
                    val canSend = minutesSinceLastPost >= 5
                    android.util.Log.d("TmmRelayService", "Minutes since last successful POST: $minutesSinceLastPost, Can send: $canSend")
                    canSend
                } else {
                    // No previous successful POST, allow this one
                    android.util.Log.d("TmmRelayService", "No previous successful POST, allowing POST")
                    true
                }
                
                if (shouldSendPost) {
                    android.util.Log.d("TmmRelayService", "Calling ApiClient.send with payload: Lat=${payload.latitude}, Lng=${payload.longitude}")
                    ApiClient.send(
                        payload.copy(deviceId = deviceId), 
                        apiKey,
                        onPostSent = { timestamp, payloadInfo, isSuccess ->
                            android.util.Log.d("TmmRelayService", "ApiClient callback received: $timestamp - $payloadInfo, Success: $isSuccess")
                            if (isSuccess) {
                                lastSuccessfulPostAt = Instant.now()
                                android.util.Log.d("TmmRelayService", "Updated lastSuccessfulPostAt to current time")
                            }
                            updateNotificationWithPost(timestamp, payloadInfo)
                        }
                    )
                } else {
                    android.util.Log.d("TmmRelayService", "Skipping POST - still in 5-minute cooldown period after last successful POST")
                }
            },
            onError = { error ->
                android.util.Log.e("TmmRelayService", "WebSocket error", error)
            }
        )

        createNotificationChannel()
        isRelayStarted = true
        startForeground(NOTIFICATION_ID, buildNotification("Started"))
        broadcastStatusUpdate("Started", null)
        wsClient.connect(tenantId, deviceId)
        handler.postDelayed(offlineCheck, TimeUnit.MINUTES.toMillis(1))
        
        // Send immediate POST call when service starts
        handler.post {
            sendInitialPost()
        }
        
        // Start periodic POST calls every 5 minutes (first one will be 5 minutes after start)
        handler.postDelayed(periodicPostCheck, TimeUnit.MINUTES.toMillis(5))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If service is already started, broadcast current status
        if (isRelayStarted) {
            val status = "Started"
            val postInfo = if (lastPostTimestamp != null && lastPostPayload != null) {
                "$lastPostTimestamp - $lastPostPayload"
            } else {
                null
            }
            broadcastStatusUpdate(status, postInfo)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRelayStarted = false
        wsClient.close()
        handler.removeCallbacksAndMessages(null)
        // Update notification to show stopped status
        updateNotification("Stopped")
        broadcastStatusUpdate("Stopped", null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(status: String): Notification {
        val statusText = "Sync Relay: $status"
        val lastPostText = if (lastPostTimestamp != null && lastPostPayload != null) {
            "\nLast POST: $lastPostTimestamp - $lastPostPayload"
        } else {
            "\nNo POST calls yet"
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TMM Relay Service")
            .setContentText(statusText + lastPostText)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(statusText + lastPostText))
            .setSmallIcon(R.drawable.ic_tracker)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = buildNotification(status)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationWithPost(timestamp: String, payloadInfo: String) {
        // Ensure this runs on the main thread
        handler.post {
            lastPostTimestamp = timestamp
            lastPostPayload = payloadInfo
            val status = if (isRelayStarted) "Started" else "Stopped"
            android.util.Log.d("TmmRelayService", "Updating notification with POST: $timestamp - $payloadInfo")
            updateNotification(status)
            // Broadcast the POST update
            val postInfo = "$timestamp - $payloadInfo"
            broadcastStatusUpdate(status, postInfo)
        }
    }

   

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
        android.util.Log.d("TmmRelayService", "Emitting offline POST")
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
        ApiClient.send(
            payload, 
            apiKey,
            onPostSent = { timestamp, payloadInfo, isSuccess ->
                android.util.Log.d("TmmRelayService", "Offline POST callback received: $timestamp - $payloadInfo, Success: $isSuccess")
                if (isSuccess) {
                    lastSuccessfulPostAt = Instant.now()
                }
                updateNotificationWithPost(timestamp, payloadInfo)
            }
        )
    }

    private fun sendInitialPost() {
        android.util.Log.d("TmmRelayService", "Sending initial POST on service start")
        val payload = TelemetryPayload(
            tenantId = tenantId,
            deviceId = DeviceInfoUtil.deviceId(this),
            latitude = lastKnownLatitude,
            longitude = lastKnownLongitude,
            battery = DeviceInfoUtil.batteryLevel(this),
            fixType = if (lastKnownFixType != "UNKNOWN") lastKnownFixType else "INITIAL",
            timestamp = Instant.now().toString(),
            health = if (lastKnownLatitude == 0.0 && lastKnownLongitude == 0.0) "NO_COORDINATES" else "OK",
            horizontalAccuracy = -1.0,
            verticalAccuracy = -1.0,
            satellites = -1
        )
        ApiClient.send(
            payload,
            apiKey,
            onPostSent = { timestamp, payloadInfo, isSuccess ->
                android.util.Log.d("TmmRelayService", "Initial POST callback received: $timestamp - $payloadInfo, Success: $isSuccess")
                if (isSuccess) {
                    lastSuccessfulPostAt = Instant.now()
                    android.util.Log.d("TmmRelayService", "Updated lastSuccessfulPostAt after initial POST")
                }
                updateNotificationWithPost(timestamp, payloadInfo)
            }
        )
    }

    private fun sendPeriodicPost() {
        android.util.Log.d("TmmRelayService", "Sending periodic POST - Lat: $lastKnownLatitude, Lng: $lastKnownLongitude")
        val payload = TelemetryPayload(
            tenantId = tenantId,
            deviceId = DeviceInfoUtil.deviceId(this),
            latitude = lastKnownLatitude,
            longitude = lastKnownLongitude,
            battery = DeviceInfoUtil.batteryLevel(this),
            fixType = if (lastKnownFixType != "UNKNOWN") lastKnownFixType else "PERIODIC",
            timestamp = Instant.now().toString(),
            health = "OK",
            horizontalAccuracy = -1.0,
            verticalAccuracy = -1.0,
            satellites = -1
        )
        ApiClient.send(
            payload,
            apiKey,
            onPostSent = { timestamp, payloadInfo, isSuccess ->
                android.util.Log.d("TmmRelayService", "Periodic POST callback received: $timestamp - $payloadInfo, Success: $isSuccess")
                if (isSuccess) {
                    lastSuccessfulPostAt = Instant.now()
                    android.util.Log.d("TmmRelayService", "Updated lastSuccessfulPostAt after periodic POST")
                }
                updateNotificationWithPost(timestamp, payloadInfo)
            }
        )
    }
    private fun broadcastStatusUpdate(status: String, postInfo: String?) {
        // Ensure broadcast is sent on main thread
        handler.post {
            val intent = Intent(ACTION_STATUS_UPDATE).apply {
                putExtra(EXTRA_STATUS, status)
                if (postInfo != null && postInfo.contains(" - ")) {
                    val parts = postInfo.split(" - ", limit = 2)
                    putExtra(EXTRA_POST_TIMESTAMP, parts[0])
                    putExtra(EXTRA_POST_PAYLOAD, parts.getOrElse(1) { "" })
                } else {
                    putExtra(EXTRA_POST_TIMESTAMP, "")
                    putExtra(EXTRA_POST_PAYLOAD, "")
                }
            }
            android.util.Log.d("TmmRelayService", "Broadcasting status: $status, postInfo: $postInfo")
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }
   

    companion object {
        private const val CHANNEL_ID = "tmm_channel"
        private const val NOTIFICATION_ID = 1
        
        // Broadcast actions
        const val ACTION_STATUS_UPDATE = "com.hirenq.tmmrelay.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_POST_TIMESTAMP = "post_timestamp"
        const val EXTRA_POST_PAYLOAD = "post_payload"
    }
}
