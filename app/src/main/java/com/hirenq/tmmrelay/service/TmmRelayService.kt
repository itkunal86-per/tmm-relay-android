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
import com.hirenq.tmmrelay.util.LocationManagerUtil
import com.hirenq.tmmrelay.util.TrimbleLicensingUtil
import android.location.Location
import java.time.Instant
import java.util.concurrent.TimeUnit

class TmmRelayService : Service() {

    private var catalystClient: CatalystClient? = null
    private var locationManagerUtil: LocationManagerUtil? = null
    private val tenantId = "ASSAM_LAND_REGISTRY"
    private val apiKey: String? = null

    private var lastMessageAt: Instant = Instant.now()
    private var lastSuccessfulPostAt: Instant? = null
    private var isRelayStarted = false

    private var lastPostTimestamp: String? = null
    private var lastPostPayload: String? = null

    // Trimble receiver data (when available)
    private var lastKnownLatitude = 0.0
    private var lastKnownLongitude = 0.0
    private var lastKnownFixType = "UNKNOWN"
    
    // Mobile GPS data (always available if location permission granted)
    private var mobileLatitude = 0.0
    private var mobileLongitude = 0.0
    private var mobileAccuracy = -1.0

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

    // -------------------- HELPER FUNCTIONS --------------------
    
    // Enrich payload with mobile GPS data
    private fun enrichPayloadWithMobileGps(payload: TelemetryPayload): TelemetryPayload {
        val mobileBattery = DeviceInfoUtil.batteryLevel(this)
        val mobileBatteryHealth = DeviceInfoUtil.getAndroidBatteryHealth(this)
        val dataSource = if (catalystClient?.getConnectionStatus() == true && 
                             (payload.latitude != 0.0 || payload.longitude != 0.0)) {
            "TRIMBLE"
        } else if (mobileLatitude != 0.0 || mobileLongitude != 0.0) {
            "MOBILE_GPS"
        } else {
            null
        }
        
        return payload.copy(
            mobileLatitude = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) mobileLatitude else null,
            mobileLongitude = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) mobileLongitude else null,
            mobileAccuracy = if (mobileAccuracy > 0) mobileAccuracy else null,
            mobileBattery = mobileBattery,
            mobileBatteryHealth = mobileBatteryHealth,
            dataSource = dataSource
        )
    }

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
            
            // Add connection status and error state
            val isConnected = catalystClient?.getConnectionStatus() ?: false
            putExtra("isConnected", isConnected)
            catalystClient?.getCurrentError()?.let { error ->
                putExtra("error", error)
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
            if (isRelayStarted) {
                // Send POST with mobile GPS data even if Trimble is not connected
                sendMobileGpsPost()
            }
            handler.postDelayed(this, TimeUnit.MINUTES.toMillis(5))
        }
    }
    
    // Periodic task to send mobile GPS data even when Trimble is not connected
    private val mobileGpsPostCheck = object : Runnable {
        override fun run() {
            if (isRelayStarted) {
                // Send mobile GPS data every 2 minutes if Trimble is not connected
                val isTrimbleConnected = catalystClient?.getConnectionStatus() ?: false
                if (!isTrimbleConnected && (mobileLatitude != 0.0 || mobileLongitude != 0.0)) {
                    sendMobileGpsPost()
                }
            }
            handler.postDelayed(this, TimeUnit.MINUTES.toMillis(2))
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
                // Use Trimble data if available, otherwise use mobile GPS
                val isTrimbleConnected = catalystClient?.getConnectionStatus() ?: false
                val latitude = if (isTrimbleConnected && (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0)) {
                    lastKnownLatitude
                } else {
                    mobileLatitude
                }
                val longitude = if (isTrimbleConnected && (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0)) {
                    lastKnownLongitude
                } else {
                    mobileLongitude
                }
                val fixType = if (isTrimbleConnected && lastKnownFixType != "UNKNOWN") {
                    lastKnownFixType
                } else if (mobileLatitude != 0.0 || mobileLongitude != 0.0) {
                    "MOBILE_GPS"
                } else {
                    "UNKNOWN"
                }
                val accuracy = if (isTrimbleConnected && lastKnownLatitude != 0.0) {
                    -1.0 // Will be set from Trimble payload if available
                } else {
                    mobileAccuracy
                }
                
                val mobileBattery = DeviceInfoUtil.batteryLevel(this@TmmRelayService)
                val dataSource = if (isTrimbleConnected && (latitude != 0.0 || longitude != 0.0)) {
                    "TRIMBLE"
                } else if (mobileLatitude != 0.0 || mobileLongitude != 0.0) {
                    "MOBILE_GPS"
                } else {
                    null
                }
                
                val payload = TelemetryPayload(
                    tenantId = tenantId,
                    deviceId = DeviceInfoUtil.deviceId(this@TmmRelayService),
                    latitude = latitude,
                    longitude = longitude,
                    battery = mobileBattery,
                    fixType = fixType,
                    timestamp = Instant.now().toString(),
                    health = if (isTrimbleConnected) "OK" else if (mobileLatitude != 0.0 || mobileLongitude != 0.0) "MOBILE_GPS_ONLY" else "OK",
                    horizontalAccuracy = accuracy,
                    verticalAccuracy = -1.0,
                    satellites = -1,
                    mobileLatitude = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) mobileLatitude else null,
                    mobileLongitude = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) mobileLongitude else null,
                    mobileAccuracy = if (mobileAccuracy > 0) mobileAccuracy else null,
                    mobileBattery = mobileBattery,
                    dataSource = dataSource
                )
                broadcastDiagnostics(payload)
            }
            handler.postDelayed(this, TimeUnit.SECONDS.toMillis(10))
        }
    }

    // -------------------- SERVICE LIFECYCLE --------------------

    override fun onCreate() {
        super.onCreate()
        
        // Get device ID early so it's available throughout onCreate
        val deviceId = DeviceInfoUtil.deviceId(this)
        android.util.Log.i("TmmRelayService", "Device ID = $deviceId")
        
        try {
            android.util.Log.i("TmmRelayService", "=== Service onCreate() started ===")
            
            // Initialize Trimble Licensing SDK early (required for Trimble SDK features)
            android.util.Log.i("TmmRelayService", "Step 1: Initializing Trimble Licensing")
            TrimbleLicensingUtil.initialize(this)
            android.util.Log.i("TmmRelayService", "Step 1: Trimble Licensing initialized")

            // Common handler for processing payloads from Catalyst client
        val payloadHandler: (TelemetryPayload) -> Unit = { payload ->
                try {
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
                android.util.Log.i("TmmRelayService", "=== Sending POST request with full payload ===")
                
                // Enrich payload with mobile GPS data before sending
                val enrichedPayload = enrichPayloadWithMobileGps(payload.copy(deviceId = deviceId))
                
                android.util.Log.i("TmmRelayService", "Payload: TenantId=${enrichedPayload.tenantId}, " +
                        "DeviceId=$deviceId, Lat=${enrichedPayload.latitude}, Lng=${enrichedPayload.longitude}, " +
                        "Battery=${enrichedPayload.battery}, FixType=${enrichedPayload.fixType}, " +
                        "Health=${enrichedPayload.health}, HAcc=${enrichedPayload.horizontalAccuracy}, " +
                        "VAcc=${enrichedPayload.verticalAccuracy}, Satellites=${enrichedPayload.satellites}, " +
                        "ReceiverBattery=${enrichedPayload.receiverBattery}, ReceiverHealth=${enrichedPayload.receiverHealth}, " +
                        "MobileLat=${enrichedPayload.mobileLatitude}, MobileLng=${enrichedPayload.mobileLongitude}, " +
                        "MobileAcc=${enrichedPayload.mobileAccuracy}, MobileBattery=${enrichedPayload.mobileBattery}, " +
                        "MobileBatteryHealth=${enrichedPayload.mobileBatteryHealth}, DataSource=${enrichedPayload.dataSource}")
                
                ApiClient.send(
                    enrichedPayload,
                    apiKey
                ) { timestamp, payloadInfo, success ->
                    android.util.Log.i("TmmRelayService", "POST response: $timestamp - $payloadInfo (success=$success)")
                    if (success) lastSuccessfulPostAt = Instant.now()
                    updateNotificationWithPost(timestamp, payloadInfo)
                    updateDynamicStatus()
                }
            } else {
                val minutesSinceLastPost = lastSuccessfulPostAt?.let {
                    java.time.Duration.between(it, Instant.now()).toMinutes()
                } ?: 0
                android.util.Log.d("TmmRelayService", "Skipping POST (last post was $minutesSinceLastPost minutes ago, need 5 minutes)")
            }
                } catch (e: Exception) {
                    android.util.Log.e("TmmRelayService", "Error in payloadHandler: ${e.message}", e)
            }
        }

            // Always use Catalyst SDK
            android.util.Log.i("TmmRelayService", "Step 3: Creating CatalystClient instance")
            catalystClient = CatalystClient(
                context = this,
                onMessage = payloadHandler,
                onError = { error ->
                    android.util.Log.e("TmmRelayService", "Catalyst error: ${error.message}", error)
                    // Broadcast error state immediately
                    val diagnosticsIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                        putExtra("locationPermission", hasLocationPermission())
                        putExtra("bluetoothPermission", hasBluetoothPermission())
                        putExtra("isConnected", false)
                        catalystClient?.getCurrentError()?.let { errorState ->
                            putExtra("error", errorState)
                        }
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(diagnosticsIntent)
                }
            )
            android.util.Log.i("TmmRelayService", "Step 3: CatalystClient created")

            android.util.Log.i("TmmRelayService", "Step 4: Creating notification channel")
            createNotificationChannel()
            android.util.Log.i("TmmRelayService", "Step 4: Notification channel created")
            
            // Initialize mobile GPS location tracking
            android.util.Log.i("TmmRelayService", "Step 5: Initializing mobile GPS location tracking")
            locationManagerUtil = LocationManagerUtil(this)
            if (locationManagerUtil?.hasLocationPermission() == true) {
                locationManagerUtil?.startLocationUpdates(
                    minTimeMs = 5000, // Update every 5 seconds
                    minDistanceM = 10f // Update if moved 10 meters
                ) { location ->
                    mobileLatitude = location.latitude
                    mobileLongitude = location.longitude
                    mobileAccuracy = location.accuracy.toDouble()
                    android.util.Log.d("TmmRelayService", "Mobile GPS updated: lat=$mobileLatitude, lon=$mobileLongitude, acc=$mobileAccuracy")
                }
                android.util.Log.i("TmmRelayService", "Step 5: Mobile GPS location tracking started")
            } else {
                android.util.Log.w("TmmRelayService", "Step 5: Location permission not granted - mobile GPS tracking disabled")
            }
            
            isRelayStarted = true

            android.util.Log.i("TmmRelayService", "Step 6: Starting foreground service")
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Started")
            )
            android.util.Log.i("TmmRelayService", "Step 6: Foreground service started")

            // Connect Catalyst client
            android.util.Log.i("TmmRelayService", "Step 7: Connecting Catalyst client")
            catalystClient?.connect(tenantId, deviceId)
            android.util.Log.i("TmmRelayService", "Step 7: Catalyst connect() called")
            
        } catch (e: Exception) {
            android.util.Log.e("TmmRelayService", "CRITICAL ERROR in onCreate(): ${e.message}", e)
            android.util.Log.e("TmmRelayService", "Exception type: ${e.javaClass.name}")
            e.printStackTrace()
            // Don't rethrow - let the service continue if possible
        }

        // Send initial diagnostics broadcast
        val mobileBattery = DeviceInfoUtil.batteryLevel(this)
        val initialPayload = TelemetryPayload(
            tenantId = tenantId,
            deviceId = deviceId,
            latitude = 0.0,
            longitude = 0.0,
            battery = mobileBattery,
            fixType = "UNKNOWN",
            timestamp = Instant.now().toString(),
            health = "OK",
            horizontalAccuracy = -1.0,
            verticalAccuracy = -1.0,
            satellites = -1,
            mobileLatitude = null,
            mobileLongitude = null,
            mobileAccuracy = null,
            mobileBattery = mobileBattery,
            dataSource = null
        )
        broadcastDiagnostics(initialPayload)

        handler.postDelayed(offlineCheck, TimeUnit.MINUTES.toMillis(1))
        handler.postDelayed(periodicPostCheck, TimeUnit.MINUTES.toMillis(5))
        handler.postDelayed(mobileGpsPostCheck, TimeUnit.MINUTES.toMillis(2))
        handler.postDelayed(statusUpdateCheck, TimeUnit.SECONDS.toMillis(30))
        handler.postDelayed(diagnosticsUpdateCheck, TimeUnit.SECONDS.toMillis(10))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY ensures service restarts if killed by system
        // Important for Android 10-16 and Samsung One UI compatibility
        return START_STICKY
    }

    override fun onDestroy() {
        isRelayStarted = false
        
        // Stop location updates
        locationManagerUtil?.stopLocationUpdates()
        
        // Close Catalyst client
        catalystClient?.close()
        
        handler.removeCallbacksAndMessages(null)
        updateNotification("Stopped")
        broadcastStatusUpdate("Stopped", null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------- POSTS --------------------

    private fun emitOffline() {
        val mobileBattery = DeviceInfoUtil.batteryLevel(this)
        val payload = TelemetryPayload(
            tenantId = tenantId,
            deviceId = DeviceInfoUtil.deviceId(this),
            latitude = 0.0,
            longitude = 0.0,
            battery = mobileBattery,
            fixType = "UNKNOWN",
            timestamp = Instant.now().toString(),
            health = "OFFLINE",
            horizontalAccuracy = -1.0,
            verticalAccuracy = -1.0,
            satellites = -1,
            mobileLatitude = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) mobileLatitude else null,
            mobileLongitude = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) mobileLongitude else null,
            mobileAccuracy = if (mobileAccuracy > 0) mobileAccuracy else null,
            mobileBattery = mobileBattery,
            dataSource = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) "MOBILE_GPS" else null
        )
        broadcastDiagnostics(payload)
    }

    private fun sendPeriodicPost() {
        val mobileBattery = DeviceInfoUtil.batteryLevel(this)
        val isTrimbleConnected = catalystClient?.getConnectionStatus() ?: false
        val dataSource = if (isTrimbleConnected && (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0)) {
            "TRIMBLE"
        } else if (mobileLatitude != 0.0 || mobileLongitude != 0.0) {
            "MOBILE_GPS"
        } else {
            null
        }
        
        val payload = TelemetryPayload(
            tenantId = tenantId,
            deviceId = DeviceInfoUtil.deviceId(this),
            latitude = lastKnownLatitude,
            longitude = lastKnownLongitude,
            battery = mobileBattery,
            fixType = lastKnownFixType,
            timestamp = Instant.now().toString(),
            health = "OK",
            horizontalAccuracy = -1.0,
            verticalAccuracy = -1.0,
            satellites = -1,
            mobileLatitude = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) mobileLatitude else null,
            mobileLongitude = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) mobileLongitude else null,
            mobileAccuracy = if (mobileAccuracy > 0) mobileAccuracy else null,
            mobileBattery = mobileBattery,
            dataSource = dataSource
        )
        broadcastDiagnostics(payload)
    }
    
    // Send POST request with mobile GPS data (even when Trimble is not connected)
    private fun sendMobileGpsPost() {
        val deviceId = DeviceInfoUtil.deviceId(this)
        val isTrimbleConnected = catalystClient?.getConnectionStatus() ?: false
        
        // Use Trimble data if available, otherwise use mobile GPS
        val latitude = if (isTrimbleConnected && (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0)) {
            lastKnownLatitude
        } else {
            mobileLatitude
        }
        
        val longitude = if (isTrimbleConnected && (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0)) {
            lastKnownLongitude
        } else {
            mobileLongitude
        }
        
        // Only send if we have valid coordinates
        if (latitude == 0.0 && longitude == 0.0) {
            android.util.Log.d("TmmRelayService", "Skipping mobile GPS POST - no valid coordinates")
            return
        }
        
        val fixType = if (isTrimbleConnected && lastKnownFixType != "UNKNOWN") {
            lastKnownFixType
        } else {
            "MOBILE_GPS"
        }
        
        val horizontalAccuracy = if (isTrimbleConnected && lastKnownLatitude != 0.0) {
            // Use Trimble accuracy if available
            -1.0 // Will be set from Trimble payload if available
        } else {
            mobileAccuracy
        }
        
        val mobileBattery = DeviceInfoUtil.batteryLevel(this)
        val dataSource = if (isTrimbleConnected && (latitude != 0.0 || longitude != 0.0)) {
            "TRIMBLE"
        } else if (mobileLatitude != 0.0 || mobileLongitude != 0.0) {
            "MOBILE_GPS"
        } else {
            null
        }
        
        val payload = TelemetryPayload(
            tenantId = tenantId,
            deviceId = deviceId,
            latitude = latitude,
            longitude = longitude,
            battery = mobileBattery, // Mobile battery
            fixType = fixType,
            timestamp = Instant.now().toString(),
            health = if (isTrimbleConnected) "OK" else "MOBILE_GPS_ONLY",
            horizontalAccuracy = horizontalAccuracy,
            verticalAccuracy = -1.0,
            satellites = -1,
            receiverBattery = null, // No receiver battery when using mobile GPS
            receiverHealth = null,
            mobileLatitude = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) mobileLatitude else null,
            mobileLongitude = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) mobileLongitude else null,
            mobileAccuracy = if (mobileAccuracy > 0) mobileAccuracy else null,
            mobileBattery = mobileBattery,
            dataSource = dataSource
        )
        
        android.util.Log.i("TmmRelayService", "=== Sending POST with ${if (isTrimbleConnected) "Trimble" else "Mobile GPS"} data ===")
        android.util.Log.i("TmmRelayService", "Payload: TenantId=$tenantId, DeviceId=$deviceId, " +
                "Lat=$latitude, Lng=$longitude, Battery=${payload.battery}, " +
                "FixType=$fixType, Health=${payload.health}, HAcc=$horizontalAccuracy, " +
                "MobileLat=${payload.mobileLatitude}, MobileLng=${payload.mobileLongitude}, " +
                "MobileAcc=${payload.mobileAccuracy}, MobileBattery=${payload.mobileBattery}, " +
                "MobileBatteryHealth=${payload.mobileBatteryHealth}, DataSource=${payload.dataSource}")
        
        // Check if we should send POST (every 5 minutes)
        val shouldSendPost =
            lastSuccessfulPostAt == null ||
            java.time.Duration.between(lastSuccessfulPostAt, Instant.now()).toMinutes() >= 5
        
        if (shouldSendPost) {
            // Enrich payload with mobile GPS data (including battery health) before sending
            val enrichedPayload = enrichPayloadWithMobileGps(payload)
            
            ApiClient.send(
                enrichedPayload,
                apiKey
            ) { timestamp, payloadInfo, success ->
                android.util.Log.i("TmmRelayService", "POST response: $timestamp - $payloadInfo (success=$success)")
                if (success) lastSuccessfulPostAt = Instant.now()
                updateNotificationWithPost(timestamp, payloadInfo)
                updateDynamicStatus()
            }
        } else {
            val minutesSinceLastPost = lastSuccessfulPostAt?.let {
                java.time.Duration.between(it, Instant.now()).toMinutes()
            } ?: 0
            android.util.Log.d("TmmRelayService", "Skipping POST (last post was $minutesSinceLastPost minutes ago, need 5 minutes)")
        }
        
        // Also broadcast diagnostics
        broadcastDiagnostics(payload)
    }

    // -------------------- NOTIFICATION & STATUS --------------------

    private fun updateDynamicStatus() {
        val status =
            if (!isRelayStarted) "Stopped"
            else if (lastSuccessfulPostAt == null) "Started (Catalyst SDK)"
            else "Waiting for Catalyst SDK"

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
