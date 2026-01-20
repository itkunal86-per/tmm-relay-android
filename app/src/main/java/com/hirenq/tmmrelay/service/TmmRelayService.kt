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
import trimble.jssi.android.catalystfacade.DriverType
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

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
    
    // Track if sensor properties have been logged (to avoid spamming logs)
    private var sensorPropertiesLogged = false

    // Trimble receiver data (when available)
    private var lastKnownLatitude = 0.0
    private var lastKnownLongitude = 0.0
    private var lastKnownFixType = "UNKNOWN"
    
    // Mobile GPS data (always available if location permission granted)
    private var mobileLatitude = 0.0
    private var mobileLongitude = 0.0
    private var mobileAccuracy = -1.0

    private val handler = Handler(Looper.getMainLooper())
    
    // Thread executor for running operations sequentially (matching demo MainModel.java line 227)
    private val threadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    /**
     * Run operation on thread (matching demo MainModel.java runOnThread() lines 1447-1454)
     * @param runnable The operation to run
     * @param newThread If true, creates a new thread. If false, uses threadExecutor (sequential execution)
     */
    private fun runOnThread(runnable: Runnable, newThread: Boolean) {
        if (newThread) {
            Thread(runnable).start()
        } else {
            threadExecutor.execute(runnable)
        }
    }

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
    
    
    
    // Handle Catalyst client errors
    private fun handleCatalystError(error: Throwable) {
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
            
            // Add connection status and error state
            val isConnected = catalystClient?.getConnectionStatus() ?: false
            putExtra("isConnected", isConnected)
            
            // GNSS coordinates: Use DA2 coordinates from payload if available, otherwise use lastKnown (which is from DA2)
            // latitude/longitude in payload are always from DA2 (defaults to 0.0 if not available)
            // mobileLatitude/mobileLongitude in payload are always from mobile GPS (nullable)
            val gnssLatitude: Double = if (isConnected && (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0)) {
                // DA2 is connected and we have DA2 coordinates - use them
                lastKnownLatitude
            } else {
                // Use payload latitude (which is from DA2 if available, otherwise 0.0)
                payload.latitude
            }
            val gnssLongitude: Double = if (isConnected && (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0)) {
                // DA2 is connected and we have DA2 coordinates - use them
                lastKnownLongitude
            } else {
                // Use payload longitude (which is from DA2 if available, otherwise 0.0)
                payload.longitude
            }
            
            // Add GNSS coordinates (DA2 coordinates - defaults to 0.0 if not available)
            putExtra("latitude", gnssLatitude)
            putExtra("longitude", gnssLongitude)
            
            // Add mobile GPS coordinates
            putExtra("mobileLatitude", payload.mobileLatitude ?: 0.0)
            putExtra("mobileLongitude", payload.mobileLongitude ?: 0.0)
            
            // Add data source: Determine if coordinates are from DA2 or Mobile GPS
            // Use payload.dataSource if available, otherwise determine from coordinates
            val dataSource: String = payload.dataSource ?: if (gnssLatitude != 0.0 || gnssLongitude != 0.0) {
                "TRIMBLE" // DA2 (Trimble) coordinates
            } else if ((payload.mobileLatitude != null && payload.mobileLatitude != 0.0) || 
                       (payload.mobileLongitude != null && payload.mobileLongitude != 0.0)) {
                "MOBILE_GPS" // Mobile GPS coordinates
            } else {
                "N/A" // No coordinates available
            }
            putExtra("dataSource", dataSource)
                     
            catalystClient?.getCurrentError()?.let { error ->
                putExtra("error", error)
            }
            
            // Add Trimble position data for display (matching demo MainActivity.updatePositionTable)
            if (isConnected) {
                catalystClient?.getLatestPosition()?.let { position ->
                    try {
                        // Solution type
                        val solution = try { position.getSolution()?.toString() } catch (e: Exception) { null }
                        putExtra("positionSolution", solution ?: "-")
                        
                        // Latitude (convert from radians to degrees)
                        val latRadians = try { position.getLatitude() } catch (e: Exception) { 0.0 }
                        val latDegrees = latRadians * 180.0 / kotlin.math.PI
                        putExtra("positionLatitude", String.format(java.util.Locale.getDefault(), "%.8f", latDegrees))
                        
                        // Longitude (convert from radians to degrees)
                        val lonRadians = try { position.getLongitude() } catch (e: Exception) { 0.0 }
                        val lonDegrees = lonRadians * 180.0 / kotlin.math.PI
                        putExtra("positionLongitude", String.format(java.util.Locale.getDefault(), "%.8f", lonDegrees))
                        
                        // Height
                        val height = try { position.getHeight() } catch (e: Exception) { 0.0 }
                        putExtra("positionHeight", String.format(java.util.Locale.getDefault(), "%.3f", height))
                        
                        // H Precision
                        val hPrecision = try { position.getHPrecision() } catch (e: Exception) { 0.0 }
                        putExtra("positionHPrecision", String.format(java.util.Locale.getDefault(), "%.3f", hPrecision))
                        
                        // V Precision
                        val vPrecision = try { position.getVPrecision() } catch (e: Exception) { 0.0 }
                        putExtra("positionVPrecision", String.format(java.util.Locale.getDefault(), "%.3f", vPrecision))
                        
                        // Correction Age
                        val correctionAge = try { position.getCorrectionAge() } catch (e: Exception) { 0.0 }
                        putExtra("positionCorrectionAge", String.format(java.util.Locale.getDefault(), "%.2f", correctionAge))
                    } catch (e: Exception) {
                        android.util.Log.e("TmmRelayService", "Error extracting position data: ${e.message}", e)
                    }
                }
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
                // Separate DA2 and mobile GPS coordinates properly
                val isTrimbleConnected = catalystClient?.getConnectionStatus() ?: false
                android.util.Log.d("TmmRelayService", "Diagnostics update check: isTrimbleConnected=$isTrimbleConnected")
                
                // DA2 coordinates (latitude/longitude) - only from DA2, defaults to 0.0 if not available
                val da2Latitude: Double = if (isTrimbleConnected && (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0)) {
                    lastKnownLatitude
                } else {
                    0.0
                }
                val da2Longitude: Double = if (isTrimbleConnected && (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0)) {
                    lastKnownLongitude
                } else {
                    0.0
                }
                
                // Mobile GPS coordinates (mobileLatitude/mobileLongitude) - only from mobile, nullable
                val mobileGpsLatitude: Double? = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) {
                    mobileLatitude
                } else {
                    null
                }
                val mobileGpsLongitude: Double? = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) {
                    mobileLongitude
                } else {
                    null
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
                val dataSource = if (isTrimbleConnected && (da2Latitude != 0.0 || da2Longitude != 0.0)) {
                    "TRIMBLE"
                } else if (mobileGpsLatitude != null || mobileGpsLongitude != null) {
                    "MOBILE_GPS"
                } else {
                    null
                }
                
                val payload = TelemetryPayload(
                    tenantId = tenantId,
                    // DA2 receiver data (nullable) - fields 5-17
                    deviceId = null, // DA2 receiver device ID (not available)
                    latitude = da2Latitude,  // DA2 coordinates only
                    longitude = da2Longitude,  // DA2 coordinates only
                    battery = null, // DA2 receiver battery (not available in diagnostics)
                    fixType = if (isTrimbleConnected && lastKnownFixType != "UNKNOWN") lastKnownFixType else null, // DA2 fix type
                    timestamp = if (isTrimbleConnected) Instant.now().toString() else null, // DA2 timestamp
                    health = if (isTrimbleConnected) "OK" else if (mobileGpsLatitude != null || mobileGpsLongitude != null) "MOBILE_GPS_ONLY" else "OK",
                    horizontalAccuracy = if (accuracy >= 0) accuracy else 0.0, // DA2 horizontal accuracy
                    verticalAccuracy = 0.0, // DA2 vertical accuracy (not available)
                    satellites = null, // DA2 satellites (not available in diagnostics)
                    userId = null, // DA2 user data
                    userName = null, // DA2 user data
                    userEmail = null, // DA2 user data
                    // Mobile GPS data (always included)
                    mobileDeviceId = DeviceInfoUtil.deviceId(this@TmmRelayService), // Mobile device ID (always present)
                    mobileLatitude = mobileGpsLatitude,  // Mobile GPS coordinates only
                    mobileLongitude = mobileGpsLongitude,  // Mobile GPS coordinates only
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

            // Update lastKnown DA2 coordinates only if payload has DA2 coordinates (latitude/longitude are from DA2)
            if (payload.latitude != 0.0 || payload.longitude != 0.0) {
                lastKnownLatitude = payload.latitude
                lastKnownLongitude = payload.longitude
                lastKnownFixType = payload.fixType ?: "UNKNOWN"
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

            // NOTE: Do NOT create CatalystClient here
            // CatalystClient should only be created when Load Subscription button is clicked
            // Start Relay only starts the telemetry relay service (POST every 5 minutes)
            android.util.Log.i("TmmRelayService", "Service initialized - CatalystClient will be created when Load Subscription is called")

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

            // NOTE: Do NOT auto-connect here. Connection should be done via Load Subscription and Connect buttons
            // Start Relay only starts the telemetry relay service (POST every 5 minutes)
            android.util.Log.i("TmmRelayService", "Service started - ready for Load Subscription and Connect")
            
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
            // DA2 receiver data (nullable) - fields 5-17
            deviceId = null, // DA2 receiver device ID (not available initially)
            latitude = 0.0,  // DA2 coordinates - 0.0 initially
            longitude = 0.0,  // DA2 coordinates - 0.0 initially
            battery = null, // DA2 receiver battery - null initially
            fixType = null, // DA2 fix type - null initially
            timestamp = null, // DA2 timestamp - null initially
            health = null, // DA2 health - null initially
            horizontalAccuracy = 0.0, // DA2 horizontal accuracy - 0.0 initially
            verticalAccuracy = 0.0, // DA2 vertical accuracy - 0.0 initially
            satellites = null, // DA2 satellites - null initially
            userId = null, // DA2 user data
            userName = null, // DA2 user data
            userEmail = null, // DA2 user data
            // Mobile GPS data (always included)
            mobileDeviceId = deviceId, // Mobile device ID (always present)
            mobileLatitude = null, // Mobile GPS coordinates - null initially
            mobileLongitude = null, // Mobile GPS coordinates - null initially
            mobileAccuracy = null, // Mobile GPS accuracy - null initially
            mobileBattery = mobileBattery, // Mobile battery (always available)
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
        
        // Handle different intent actions
        when (intent?.action) {
            ACTION_CONNECT -> {
                // Connect to sensor (matching demo MainModel.beginConnect() - runs on thread)
                android.util.Log.i("TmmRelayService", "Received CONNECT action")
                runOnThread(Runnable {
                    try {
                        if (catalystClient == null) {
                            android.util.Log.w("TmmRelayService", "Cannot connect - CatalystClient is null. Service may not be started.")
                            val connectIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                putExtra("connectStatus", "Cannot connect - Service not started")
                            }
                            LocalBroadcastManager.getInstance(this).sendBroadcast(connectIntent)
                            return@Runnable
                        }
                        
                        // Show "Connecting" status (matching demo MainModel.connect() line 593)
                        val connectingIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                            putExtra("connectStatus", "Connecting...")
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(connectingIntent)
                        
                        android.util.Log.i("TmmRelayService", "Connecting to sensor...")
                        val connectRc = catalystClient?.connectToSensor()
                        if (connectRc?.code == trimble.jssi.android.catalystfacade.DriverReturnCode.Success) {
                            android.util.Log.i("TmmRelayService", "✅ Sensor connected successfully")
                            val connectIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                putExtra("connectStatus", "Sensor connected successfully")
                            }
                            LocalBroadcastManager.getInstance(this).sendBroadcast(connectIntent)
                        } else if (connectRc?.code == trimble.jssi.android.catalystfacade.DriverReturnCode.ErrorNoLicense) {
                            android.util.Log.w("TmmRelayService", "⚠️ Sensor is not licensed")
                            val connectIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                putExtra("connectStatus", "The instrument is not licensed")
                            }
                            LocalBroadcastManager.getInstance(this).sendBroadcast(connectIntent)
                        } else {
                            android.util.Log.w("TmmRelayService", "⚠️ Connect failed: ${connectRc?.code}")
                            val connectIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                putExtra("connectStatus", "Unable to connect")
                            }
                            LocalBroadcastManager.getInstance(this).sendBroadcast(connectIntent)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TmmRelayService", "Error connecting: ${e.message}", e)
                        val connectIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                            putExtra("connectStatus", "Unable to connect: ${e.message}")
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(connectIntent)
                    }
                }, false)
            }
            ACTION_DISCONNECT -> {
                // Disconnect from Trimble sensor (matching demo MainModel.beginDisconnect())
                android.util.Log.i("TmmRelayService", "Received DISCONNECT action")
                runOnThread(Runnable {
                    try {
                        val isConnected = catalystClient?.getConnectionStatus() ?: false
                        if (!isConnected) {
                            android.util.Log.i("TmmRelayService", "Already disconnected from Trimble")
                            val disconnectIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                putExtra("disconnectStatus", "Already disconnected")
                            }
                            LocalBroadcastManager.getInstance(this).sendBroadcast(disconnectIntent)
                        } else {
                            android.util.Log.i("TmmRelayService", "Disconnecting from Trimble...")
                            val disconnectRc = catalystClient?.disconnect()
                            if (disconnectRc?.code == trimble.jssi.android.catalystfacade.DriverReturnCode.Success) {
                                android.util.Log.i("TmmRelayService", "✅ Disconnected successfully")
                                val disconnectIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                    putExtra("disconnectStatus", "Disconnected")
                                }
                                LocalBroadcastManager.getInstance(this).sendBroadcast(disconnectIntent)
                            } else {
                                android.util.Log.w("TmmRelayService", "⚠️ Disconnect returned: ${disconnectRc?.code}")
                                val disconnectIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                    putExtra("disconnectStatus", "Disconnect failed: ${disconnectRc?.code}")
                                }
                                LocalBroadcastManager.getInstance(this).sendBroadcast(disconnectIntent)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TmmRelayService", "Error disconnecting: ${e.message}", e)
                        val disconnectIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                            putExtra("disconnectStatus", "Disconnect failed: ${e.message}")
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(disconnectIntent)
                    }
                }, false)
            }
            ACTION_LOAD_SUBSCRIPTION -> {
                // Load subscription and initialize driver (matching demo MainModel.beginLoadSubscription() - Subscribe button)
                android.util.Log.i("TmmRelayService", "Received LOAD_SUBSCRIPTION action")
                runOnThread(Runnable {
                    try {
                        // Create CatalystClient if it doesn't exist
                        if (catalystClient == null) {
                            android.util.Log.i("TmmRelayService", "Creating CatalystClient for Load Subscription...")
                            
                            // Get userTID from SharedPreferences (set by MainActivity after TMM Login)
                            val userTID = getSharedPreferences("TmmRelayPrefs", Context.MODE_PRIVATE)
                                .getString("userTID", null)
                            if (userTID != null) {
                                android.util.Log.i("TmmRelayService", "Using userTID from TMM Login: $userTID")
                            } else {
                                android.util.Log.i("TmmRelayService", "No userTID found - will use default TMM subscription")
                            }
                            
                            // Common handler for processing payloads from Catalyst client
                            val payloadHandler: (TelemetryPayload) -> Unit = { payload ->
                                try {
                                    lastMessageAt = Instant.now()

                                    // Update lastKnown DA2 coordinates only if payload has DA2 coordinates
                                    if (payload.latitude != 0.0 || payload.longitude != 0.0) {
                                        lastKnownLatitude = payload.latitude
                                        lastKnownLongitude = payload.longitude
                                        lastKnownFixType = payload.fixType ?: "UNKNOWN"
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
                                        val enrichedPayload = enrichPayloadWithMobileGps(payload.copy(deviceId = payload.deviceId))
                                        
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
                            
                            catalystClient = CatalystClient(
                                context = this,
                                onMessage = payloadHandler,
                                onError = { error ->
                                    handleCatalystError(error)
                                },
                                userTID = userTID
                            )
                            android.util.Log.i("TmmRelayService", "CatalystClient created")
                        }
                        
                        android.util.Log.i("TmmRelayService", "Loading subscription...")
                        val prefs = getSharedPreferences("TmmRelayPrefs", Context.MODE_PRIVATE)
                        val tenantId = prefs.getString("tenantId", "") ?: ""
                        val deviceId = prefs.getString("deviceId", "") ?: ""
                        
                        catalystClient?.loadSubscription(tenantId, deviceId)
                        
                        val loadSubIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                            putExtra("loadSubStatus", "Loading subscription...")
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(loadSubIntent)
                    } catch (e: Exception) {
                        android.util.Log.e("TmmRelayService", "Error loading subscription: ${e.message}", e)
                        val loadSubIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                            putExtra("loadSubStatus", "Load subscription failed: ${e.message}")
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(loadSubIntent)
                    }
                }, false)
            }
            ACTION_START_SURVEY -> {
                // Start survey if Trimble is connected and licensed (matching demo MainModel.beginStartSurvey())
                android.util.Log.i("TmmRelayService", "Received START_SURVEY action")
                runOnThread(Runnable {
                    try {
                        val isConnected = catalystClient?.getConnectionStatus() ?: false
                        if (isConnected) {
                            android.util.Log.i("TmmRelayService", "Trimble is connected - starting survey...")
                            val surveyRc = catalystClient?.startSurvey()
                            if (surveyRc?.code == trimble.jssi.android.catalystfacade.DriverReturnCode.Success) {
                                android.util.Log.i("TmmRelayService", "✅ Survey started successfully")
                                // Broadcast success
                                val surveyIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                    putExtra("surveyStatus", "Survey Started")
                                }
                                LocalBroadcastManager.getInstance(this).sendBroadcast(surveyIntent)
                            } else {
                                android.util.Log.w("TmmRelayService", "⚠️ Survey start failed: ${surveyRc?.code}")
                                val surveyIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                    putExtra("surveyStatus", "Survey Start Failed: ${surveyRc?.code}")
                                }
                                LocalBroadcastManager.getInstance(this).sendBroadcast(surveyIntent)
                            }
                        } else {
                            android.util.Log.w("TmmRelayService", "Cannot start survey - Trimble not connected")
                            val surveyIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                putExtra("surveyStatus", "Cannot start survey - Trimble not connected")
                            }
                            LocalBroadcastManager.getInstance(this).sendBroadcast(surveyIntent)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TmmRelayService", "Error starting survey: ${e.message}", e)
                    }
                }, false)
            }
            ACTION_END_SURVEY -> {
                // End survey (matching demo MainModel.beginEndSurvey())
                android.util.Log.i("TmmRelayService", "Received END_SURVEY action")
                runOnThread(Runnable {
                    try {
                        val isConnected = catalystClient?.getConnectionStatus() ?: false
                        if (isConnected) {
                            android.util.Log.i("TmmRelayService", "Ending survey...")
                            val endSurveyRc = catalystClient?.endSurvey()
                            if (endSurveyRc?.code == trimble.jssi.android.catalystfacade.DriverReturnCode.Success) {
                                android.util.Log.i("TmmRelayService", "✅ Survey ended successfully")
                                val surveyIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                    putExtra("surveyStatus", "Survey Ended")
                                }
                                LocalBroadcastManager.getInstance(this).sendBroadcast(surveyIntent)
                            } else {
                                android.util.Log.w("TmmRelayService", "⚠️ End survey failed: ${endSurveyRc?.code}")
                                val surveyIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                    putExtra("surveyStatus", "End Survey Failed: ${endSurveyRc?.code}")
                                }
                                LocalBroadcastManager.getInstance(this).sendBroadcast(surveyIntent)
                            }
                        } else {
                            android.util.Log.w("TmmRelayService", "Cannot end survey - Trimble not connected")
                            val surveyIntent = Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                                putExtra("surveyStatus", "Cannot end survey - Trimble not connected")
                            }
                            LocalBroadcastManager.getInstance(this).sendBroadcast(surveyIntent)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TmmRelayService", "Error ending survey: ${e.message}", e)
                    }
                }, false)
            }
            else -> {
                // Get userTID from intent if provided (e.g., after TMM Login)
                val intentUserTID = intent?.getStringExtra("userTID")
                if (intentUserTID != null && intentUserTID.isNotEmpty()) {
                    // Update SharedPreferences with new userTID
                    getSharedPreferences("TmmRelayPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("userTID", intentUserTID)
                        .apply()
                    android.util.Log.i("TmmRelayService", "Received userTID from intent: $intentUserTID")
                    
                    // If CatalystClient exists, restart connection with new userTID
                    // Note: This requires recreating CatalystClient since userTID is set at construction
                    // For now, service restart is required for userTID changes
                }
            }
        }
        
        return START_STICKY
    }

    override fun onDestroy() {
        // Shutdown thread executor (matching demo pattern - cleanup resources)
        try {
            threadExecutor.shutdown()
            if (!threadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                threadExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            android.util.Log.w("TmmRelayService", "Error shutting down thread executor: ${e.message}")
            threadExecutor.shutdownNow()
        }
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
            // DA2 receiver data (nullable) - fields 5-17
            deviceId = null, // DA2 receiver device ID (not available)
            latitude = if (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0) lastKnownLatitude else 0.0,  // DA2 coordinates only
            longitude = if (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0) lastKnownLongitude else 0.0,  // DA2 coordinates only
            battery = null, // DA2 receiver battery (not available)
            fixType = null, // DA2 fix type (not available when offline)
            timestamp = null, // DA2 timestamp (not available when offline)
            health = "OFFLINE", // DA2 health
            horizontalAccuracy = 0.0, // DA2 horizontal accuracy (not available)
            verticalAccuracy = 0.0, // DA2 vertical accuracy (not available)
            satellites = null, // DA2 satellites (not available)
            userId = null, // DA2 user data
            userName = null, // DA2 user data
            userEmail = null, // DA2 user data
            // Mobile GPS data (always included)
            mobileDeviceId = DeviceInfoUtil.deviceId(this), // Mobile device ID (always present)
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
            // DA2 receiver data (nullable) - fields 5-17
            deviceId = null, // DA2 receiver device ID (not available)
            latitude = if (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0) lastKnownLatitude else 0.0,  // DA2 coordinates only
            longitude = if (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0) lastKnownLongitude else 0.0,  // DA2 coordinates only
            battery = null, // DA2 receiver battery (not available in periodic post)
            fixType = if (isTrimbleConnected && lastKnownFixType != "UNKNOWN") lastKnownFixType else null, // DA2 fix type
            timestamp = if (isTrimbleConnected) Instant.now().toString() else null, // DA2 timestamp
            health = if (isTrimbleConnected) "OK" else null, // DA2 health
            horizontalAccuracy = 0.0, // DA2 horizontal accuracy (not available)
            verticalAccuracy = 0.0, // DA2 vertical accuracy (not available)
            satellites = null, // DA2 satellites (not available)
            userId = null, // DA2 user data
            userName = null, // DA2 user data
            userEmail = null, // DA2 user data
            // Mobile GPS data (always included)
            mobileDeviceId = DeviceInfoUtil.deviceId(this), // Mobile device ID (always present)
            mobileLatitude = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) mobileLatitude else null,  // Mobile GPS coordinates only
            mobileLongitude = if (mobileLatitude != 0.0 || mobileLongitude != 0.0) mobileLongitude else null,  // Mobile GPS coordinates only
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
        
        // This method sends POST with mobile GPS data - separate DA2 and mobile GPS coordinates
        // Only send if we have valid mobile GPS coordinates (don't mix with DA2)
        if (mobileLatitude == 0.0 && mobileLongitude == 0.0) {
            android.util.Log.d("TmmRelayService", "Skipping mobile GPS POST - no valid mobile GPS coordinates")
            return
        }
        
        val fixType = "MOBILE_GPS"  // This method is specifically for mobile GPS
        
        val mobileBattery = DeviceInfoUtil.batteryLevel(this)
        val dataSource = "MOBILE_GPS"
        
        // DA2 coordinates (latitude/longitude) - defaults to 0.0 if not available, only from DA2 if available
        // Mobile GPS coordinates (mobileLatitude/mobileLongitude) - always included
        val payload = TelemetryPayload(
            tenantId = tenantId,
            // DA2 receiver data (nullable) - fields 5-17
            deviceId = null, // DA2 receiver device ID (not available)
            latitude = if (isTrimbleConnected && (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0)) lastKnownLatitude else 0.0,  // DA2 coordinates only
            longitude = if (isTrimbleConnected && (lastKnownLatitude != 0.0 || lastKnownLongitude != 0.0)) lastKnownLongitude else 0.0,  // DA2 coordinates only
            battery = null, // DA2 receiver battery (not available)
            fixType = null, // DA2 fix type (not available when using mobile GPS)
            timestamp = null, // DA2 timestamp (not available when using mobile GPS)
            health = null, // DA2 health (not available when using mobile GPS)
            horizontalAccuracy = 0.0, // DA2 horizontal accuracy (not available)
            verticalAccuracy = 0.0, // DA2 vertical accuracy (not available)
            satellites = null, // DA2 satellites (not available)
            userId = null, // DA2 user data
            userName = null, // DA2 user data
            userEmail = null, // DA2 user data
            // Mobile GPS data (always included)
            mobileDeviceId = deviceId, // Mobile device ID (always present)
            mobileLatitude = mobileLatitude,  // Mobile GPS coordinates only
            mobileLongitude = mobileLongitude,  // Mobile GPS coordinates only
            mobileAccuracy = if (mobileAccuracy > 0) mobileAccuracy else null,
            mobileBattery = mobileBattery,
            dataSource = dataSource
        )
        
        android.util.Log.i("TmmRelayService", "=== Sending POST with ${if (isTrimbleConnected) "Trimble" else "Mobile GPS"} data ===")
        android.util.Log.i("TmmRelayService", "Payload: TenantId=$tenantId, MobileDeviceId=${payload.mobileDeviceId}, " +
                "DA2DeviceId=${payload.deviceId}, DA2Lat=${payload.latitude}, DA2Lng=${payload.longitude}, " +
                "DA2Battery=${payload.battery}, DA2FixType=${payload.fixType}, DA2Health=${payload.health}, " +
                "DA2HAcc=${payload.horizontalAccuracy}, " +
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
        const val ACTION_START_SURVEY =
            "com.hirenq.tmmrelay.START_SURVEY"
        const val ACTION_END_SURVEY =
            "com.hirenq.tmmrelay.END_SURVEY"
        const val ACTION_LOAD_SUBSCRIPTION =
            "com.hirenq.tmmrelay.LOAD_SUBSCRIPTION"
        const val ACTION_CONNECT =
            "com.hirenq.tmmrelay.CONNECT"
        const val ACTION_DISCONNECT =
            "com.hirenq.tmmrelay.DISCONNECT"

        const val EXTRA_STATUS = "status"
        const val EXTRA_POST_TIMESTAMP = "post_timestamp"
        const val EXTRA_POST_PAYLOAD = "post_payload"
    }
}
