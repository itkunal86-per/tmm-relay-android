package com.hirenq.tmmrelay.service

import android.content.Context
import android.util.Log
import com.hirenq.tmmrelay.model.TelemetryPayload
import com.hirenq.tmmrelay.util.DeviceInfoUtil
import com.hirenq.tmmrelay.util.TrimbleLicensingUtil
import trimble.jssi.android.catalystfacade.CatalystFacade
import trimble.jssi.android.catalystfacade.DriverReturnCode
import trimble.jssi.android.catalystfacade.DriverType
import trimble.jssi.android.catalystfacade.ICatalystEventListener
import trimble.jssi.android.catalystfacade.PositionUpdate
import trimble.jssi.android.catalystfacade.PowerSourceState
import trimble.jssi.android.catalystfacade.ReturnCode
import trimble.jssi.android.catalystfacade.SatelliteUpdate
import trimble.jssi.android.catalystfacade.SensorStateEvent
import trimble.jssi.android.catalystfacade.ImuStateEvent
import java.time.Instant
import kotlin.math.PI

class CatalystClient(
    private val context: Context,
    private val onMessage: (TelemetryPayload) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {

    private val TAG = "CatalystClient"
    private var facade: CatalystFacade? = null
    private var tenantId: String = ""
    private var deviceId: String = ""
    private var isConnected = false
    private var currentError: String? = null
    private var lastDataReceivedAt: Instant? = null
    private var sdkConnected = false // Track SDK connection separately from actual data reception
    
    fun getConnectionStatus(): Boolean {
        // Only consider connected if we've received data recently (within last 30 seconds)
        return if (lastDataReceivedAt != null) {
            val secondsSinceLastData = java.time.Duration.between(lastDataReceivedAt, Instant.now()).seconds
            sdkConnected && secondsSinceLastData < 30
        } else {
            false
        }
    }
    fun getCurrentError(): String? = currentError
    
    // Track latest values from different event types
    private var latestPosition: PositionUpdate? = null
    private var latestSatellites: SatelliteUpdate? = null
    private var latestSatellitesInView: Int = 0
    private var latestBattery: PowerSourceState? = null
    private var latestHealth: SensorStateEvent? = null

    private val eventListener = object : ICatalystEventListener {
        override fun onPositionUpdate(positionUpdate: PositionUpdate) {
            try {
                // Mark that we received data from the receiver
                lastDataReceivedAt = Instant.now()
                if (!isConnected) {
                    isConnected = true
                    currentError = null
                    Log.i(TAG, "Receiver connected - received position data")
                }
                
                latestPosition = positionUpdate
                // Convert radians to degrees for latitude/longitude
                // Use explicit getter method calls for Java compatibility
                val latRadians = try { positionUpdate.getLatitude() } catch (e: Exception) { 
                    Log.e(TAG, "Error accessing latitude: ${e.message}", e)
                    0.0 
                }
                val lonRadians = try { positionUpdate.getLongitude() } catch (e: Exception) { 
                    Log.e(TAG, "Error accessing longitude: ${e.message}", e)
                    0.0 
                }
                val latDegrees = latRadians * 180.0 / PI
                val lonDegrees = lonRadians * 180.0 / PI
                
                val hPrec = try { positionUpdate.getHPrecision() } catch (e: Exception) { 
                    Log.e(TAG, "Error accessing hPrecision: ${e.message}", e)
                    Double.NaN 
                }
                val solution = try { positionUpdate.getSolution() } catch (e: Exception) { 
                    Log.e(TAG, "Error accessing solution: ${e.message}", e)
                    null 
                }
                
                Log.d(TAG, "Position: lat=$latDegrees, lon=$lonDegrees, acc=$hPrec, fix=$solution")
                createAndSendTelemetry()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onPositionUpdate: ${e.message}", e)
                Log.e(TAG, "Exception type: ${e.javaClass.name}")
                e.printStackTrace()
                onError(e)
            }
        }

        override fun onSatelliteUpdate(satelliteUpdate: SatelliteUpdate, satellitesInView: Int) {
            try {
                // Mark that we received data from the receiver
                lastDataReceivedAt = Instant.now()
                if (!isConnected) {
                    isConnected = true
                    currentError = null
                    Log.i(TAG, "Receiver connected - received satellite data")
                }
                
                latestSatellites = satelliteUpdate
                latestSatellitesInView = satellitesInView
                val satellites = try { satelliteUpdate.getSatellites() } catch (e: Exception) { 
                    Log.e(TAG, "Error accessing satellites: ${e.message}", e)
                    emptyList() 
                }
                Log.d(TAG, "Satellites: count=$satellitesInView, total=${satellites.size}")
                // Update telemetry if we have position
                latestPosition?.let { createAndSendTelemetry() }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onSatelliteUpdate: ${e.message}", e)
                e.printStackTrace()
            }
        }

        override fun onPowerUpdate(powerSourceState: PowerSourceState) {
            try {
                // Mark that we received data from the receiver
                lastDataReceivedAt = Instant.now()
                if (!isConnected) {
                    isConnected = true
                    currentError = null
                    Log.i(TAG, "Receiver connected - received power data")
                }
                
                latestBattery = powerSourceState
                val batteryLevel = try { powerSourceState.getBatteryLevel() } catch (e: Exception) { -1 }
                val isCharging = try { powerSourceState.isCharging() } catch (e: Exception) { false }
                Log.d(TAG, "Battery: ${batteryLevel}%, charging=$isCharging")
            } catch (e: Exception) {
                Log.e(TAG, "Error in onPowerUpdate: ${e.message}", e)
                e.printStackTrace()
            }
        }

        override fun onSensorStateChanged(sensorStateEvent: SensorStateEvent) {
            try {
                // Mark that we received data from the receiver
                lastDataReceivedAt = Instant.now()
                if (!isConnected) {
                    isConnected = true
                    currentError = null
                    Log.i(TAG, "Receiver connected - received sensor state data")
                }
                
                latestHealth = sensorStateEvent
                val sensorState = try { sensorStateEvent.getSensorState() } catch (e: Exception) { null }
                Log.d(TAG, "Sensor state: $sensorState")
            } catch (e: Exception) {
                Log.e(TAG, "Error in onSensorStateChanged: ${e.message}", e)
                e.printStackTrace()
            }
        }

        override fun onRtkServiceAvailable() {
            Log.d(TAG, "RTK service available")
        }

        override fun onRtxServiceAvailable() {
            Log.d(TAG, "RTX service available")
        }

        override fun onRtkConnectionStatusUpdate(rtkConnectionStatus: trimble.jssi.android.catalystfacade.RtkConnectionStatus) {
            Log.d(TAG, "RTK connection status: $rtkConnectionStatus")
        }

        override fun onSurveyTypeUpdate(surveyType: trimble.jssi.android.catalystfacade.SurveyType) {
            Log.d(TAG, "Survey type: $surveyType")
        }

        override fun onSensorOutsideGeofence() {
            Log.w(TAG, "Sensor outside geofence")
        }

        override fun onImuStateChanged(imuStateEvent: trimble.jssi.android.catalystfacade.ImuStateEvent) {
            Log.d(TAG, "IMU state changed: ${imuStateEvent.imuState}")
        }

        override fun onUsbConnectionErrorOccured() {
            Log.e(TAG, "USB connection error occurred")
            isConnected = false
            sdkConnected = false
            lastDataReceivedAt = null
            onError(RuntimeException("USB connection error"))
        }

        override fun onSubscriptionHasExpired() {
            Log.e(TAG, "Subscription has expired")
            currentError = "NO_SUBSCRIPTION"
            isConnected = false
            sdkConnected = false
            lastDataReceivedAt = null
            onError(RuntimeException("Subscription has expired"))
        }
    }

    fun connect(tenantId: String, deviceId: String) {
        this.tenantId = tenantId
        this.deviceId = deviceId
        
        // Run initialization on a background thread to avoid blocking
        Thread {
            try {
                Log.i(TAG, "=== Starting Catalyst Client Initialization ===")
                Log.i(TAG, "NOTE: Connection requires valid Trimble subscription/license")
                Log.i(TAG, "      - Subscription can be loaded from Trimble Mobile Manager (TMM)")
                Log.i(TAG, "      - Or from a subscription file if TMM is not available")
                
                Log.i(TAG, "Step 1: Initializing Trimble Licensing")
                // Initialize Trimble Licensing before using SDK
                val licensingResult = TrimbleLicensingUtil.initialize(context)
                if (licensingResult) {
                    Log.i(TAG, "✓ Trimble Licensing initialized successfully")
                } else {
                    Log.w(TAG, "⚠ Trimble Licensing initialization returned false (may still work)")
                }
                
                Log.i(TAG, "Step 2: Getting app GUID")
                // Get app GUID from application ID
                val appGuid = context.packageName
                Log.d(TAG, "Using app GUID: $appGuid")
                
                Log.i(TAG, "Step 3: Creating CatalystFacade instance")
                // Create CatalystFacade instance
                // Note: CatalystFacade constructor uses getExternalFilesDir which may require storage permissions
                facade = try {
                    val appContext = context.applicationContext
                    Log.d(TAG, "Using application context: ${appContext.packageName}")
                    val facadeInstance = CatalystFacade(appGuid, appContext)
                    Log.d(TAG, "CatalystFacade instance created successfully")
                    facadeInstance
                } catch (e: NullPointerException) {
                    Log.e(TAG, "CRITICAL: NullPointerException creating CatalystFacade - may need storage permissions", e)
                    Log.e(TAG, "Exception message: ${e.message}")
                    e.printStackTrace()
                    onError(RuntimeException("Failed to create CatalystFacade: Storage access may be required", e))
                    return@Thread
                } catch (e: Exception) {
                    Log.e(TAG, "CRITICAL: Failed to create CatalystFacade: ${e.message}", e)
                    Log.e(TAG, "Exception type: ${e.javaClass.name}")
                    e.printStackTrace()
                    onError(e)
                    return@Thread
                }
                
                Log.i(TAG, "Step 4: Loading subscription")
                // Try to load subscription from Trimble Mobile Manager first (if TMM is installed)
                // Fall back to loadSubscription() if TMM method is not available
                val loadRc = try {
                    // First, try loading from Trimble Mobile Manager
                    try {
                        val method = facade!!.javaClass.getMethod("loadSubscriptionFromTrimbleMobileManager")
                        Log.i(TAG, "Attempting to load subscription from Trimble Mobile Manager...")
                        method.invoke(facade) as ReturnCode
                    } catch (e: NoSuchMethodException) {
                        // Method doesn't exist, try standard loadSubscription
                        Log.i(TAG, "TMM method not available, trying standard loadSubscription()...")
                        facade!!.loadSubscription()
                    } catch (e: Exception) {
                        // If TMM method fails, try standard loadSubscription
                        Log.w(TAG, "TMM subscription load failed: ${e.message}, trying standard method...")
                        facade!!.loadSubscription()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "CRITICAL: Exception during loadSubscription: ${e.message}", e)
                    Log.e(TAG, "Exception type: ${e.javaClass.name}")
                    e.printStackTrace()
                    currentError = "NO_SUBSCRIPTION"
                    onError(e)
                    return@Thread
                }
                
                Log.i(TAG, "Load subscription return code: ${loadRc.code}")
                Log.i(TAG, "Return code details: ${loadRc.code.toString()}")
                
                if (loadRc.code != DriverReturnCode.Success) {
                    // Map return code to specific error - check enum values safely
                    val codeStr = loadRc.code.toString()
                    currentError = try {
                        when {
                            codeStr.contains("NoSubscription", ignoreCase = true) -> "NO_SUBSCRIPTION"
                            codeStr.contains("NotLicensed", ignoreCase = true) -> "NOT_LICENSED"
                            codeStr.contains("Subscription", ignoreCase = true) -> "NO_SUBSCRIPTION"
                            codeStr.contains("License", ignoreCase = true) -> "NOT_LICENSED"
                            else -> {
                                Log.w(TAG, "Unknown subscription error code: $codeStr")
                                "NO_SUBSCRIPTION" // Default to subscription error
                            }
                        }
                    } catch (e: Exception) {
                        "NO_SUBSCRIPTION" // Fallback
                    }
                    val error = RuntimeException("Failed to load subscription with code: ${loadRc.code} ($codeStr)")
                    Log.e(TAG, "=== SUBSCRIPTION LOAD FAILED ===")
                    Log.e(TAG, "Error type: $currentError")
                    Log.e(TAG, "Return code: ${loadRc.code}")
                    Log.e(TAG, "This usually means:")
                    Log.e(TAG, "  1. No valid Trimble subscription/license is available")
                    Log.e(TAG, "  2. Trimble Mobile Manager (TMM) is not installed or not running")
                    Log.e(TAG, "  3. Subscription file is missing or invalid")
                    Log.e(TAG, "  4. License has expired")
                    onError(error)
                    return@Thread
                }
                currentError = null // Clear error on success
                Log.i(TAG, "✓ Subscription loaded successfully")
                
                Log.i(TAG, "Step 5: Initializing driver")
                // Initialize driver for Catalyst
                val initRc = try {
                    facade!!.initDriver(DriverType.Catalyst)
                } catch (e: Exception) {
                    Log.e(TAG, "CRITICAL: Exception during initDriver: ${e.message}", e)
                    Log.e(TAG, "Exception type: ${e.javaClass.name}")
                    e.printStackTrace()
                    onError(e)
                    return@Thread
                }
                Log.d(TAG, "Init driver return code: ${initRc.code}")
                
                if (initRc.code != DriverReturnCode.Success) {
                    // Map return code to specific error - check enum values safely
                    currentError = try {
                        val codeStr = initRc.code.toString()
                        when {
                            codeStr.contains("ReceiverNotSupported", ignoreCase = true) -> "RECEIVER_NOT_SUPPORTED"
                            codeStr.contains("NoBluetoothPermission", ignoreCase = true) -> "NO_BLUETOOTH_PERMISSION"
                            codeStr.contains("Bluetooth", ignoreCase = true) -> "NO_BLUETOOTH_PERMISSION"
                            codeStr.contains("NotLicensed", ignoreCase = true) -> "NOT_LICENSED"
                            codeStr.contains("License", ignoreCase = true) -> "NOT_LICENSED"
                            codeStr.contains("Receiver", ignoreCase = true) -> "RECEIVER_NOT_SUPPORTED"
                            else -> "RECEIVER_NOT_SUPPORTED" // Default to receiver error
                        }
                    } catch (e: Exception) {
                        "RECEIVER_NOT_SUPPORTED" // Fallback
                    }
                    val error = RuntimeException("Failed to initialize driver with code: ${initRc.code}")
                    Log.e(TAG, "Failed to initialize driver: $currentError", error)
                    onError(error)
                    return@Thread
                }
                currentError = null // Clear error on success
                
                Log.i(TAG, "Step 6: Adding event listener")
                // Add event listener
                try {
                    facade!!.addCatalystEventListener(eventListener)
                    Log.d(TAG, "Event listener added successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "CRITICAL: Exception during addCatalystEventListener: ${e.message}", e)
                    Log.e(TAG, "Exception type: ${e.javaClass.name}")
                    e.printStackTrace()
                    onError(e)
                    return@Thread
                }
                
                Log.i(TAG, "Step 7: Connecting to sensor")
                // Connect to sensor (this may take time, especially on first connection)
                val connectRc = try {
                    facade!!.connect()
                } catch (e: Exception) {
                    Log.e(TAG, "CRITICAL: Exception during connect: ${e.message}", e)
                    Log.e(TAG, "Exception type: ${e.javaClass.name}")
                    e.printStackTrace()
                    onError(e)
                    return@Thread
                }
                Log.d(TAG, "Connect return code: ${connectRc.code}")
                
                if (connectRc.code != DriverReturnCode.Success) {
                    // Map return code to specific error - check enum values safely
                    currentError = try {
                        val codeStr = connectRc.code.toString()
                        when {
                            codeStr.contains("NoBluetoothPermission", ignoreCase = true) -> "NO_BLUETOOTH_PERMISSION"
                            codeStr.contains("Bluetooth", ignoreCase = true) -> "NO_BLUETOOTH_PERMISSION"
                            codeStr.contains("ReceiverNotSupported", ignoreCase = true) -> "RECEIVER_NOT_SUPPORTED"
                            codeStr.contains("Receiver", ignoreCase = true) -> "RECEIVER_NOT_SUPPORTED"
                            codeStr.contains("NoSubscription", ignoreCase = true) -> "NO_SUBSCRIPTION"
                            codeStr.contains("Subscription", ignoreCase = true) -> "NO_SUBSCRIPTION"
                            codeStr.contains("NotLicensed", ignoreCase = true) -> "NOT_LICENSED"
                            codeStr.contains("License", ignoreCase = true) -> "NOT_LICENSED"
                            else -> "CONNECTION_FAILED" // Generic connection error
                        }
                    } catch (e: Exception) {
                        "CONNECTION_FAILED" // Fallback
                    }
                    val error = RuntimeException("Failed to connect with code: ${connectRc.code}")
                    Log.e(TAG, "Failed to connect: $currentError", error)
                    isConnected = false
                    sdkConnected = false
                    lastDataReceivedAt = null
                    onError(error)
                    return@Thread
                }
                
                sdkConnected = true
                // Don't set isConnected = true yet - wait for actual data from receiver
                currentError = null // Clear error on successful connection
                Log.i(TAG, "=== SDK connected - waiting for receiver data... ===")
                
            } catch (e: Exception) {
                Log.e(TAG, "=== FATAL ERROR in Catalyst initialization ===", e)
                Log.e(TAG, "Exception type: ${e.javaClass.name}")
                Log.e(TAG, "Exception message: ${e.message}")
                e.printStackTrace()
                onError(e)
            }
        }.start()
    }
    
    private fun createAndSendTelemetry() {
        val position = latestPosition ?: return
        
        try {
            // Convert radians to degrees for latitude/longitude
            // Use explicit getter method calls for Java compatibility
            val latRadians = try { position.getLatitude() } catch (e: Exception) { 
                Log.e(TAG, "Error accessing latitude in createAndSendTelemetry: ${e.message}", e)
                0.0 
            }
            val lonRadians = try { position.getLongitude() } catch (e: Exception) { 
                Log.e(TAG, "Error accessing longitude in createAndSendTelemetry: ${e.message}", e)
                0.0 
            }
            val latDegrees = latRadians * 180.0 / PI
            val lonDegrees = lonRadians * 180.0 / PI
            
            // Map SolutionType to String
            val solution = try { position.getSolution() } catch (e: Exception) { 
                Log.e(TAG, "Error accessing solution: ${e.message}", e)
                null 
            }
            val fixTypeName = solution?.toString() ?: "UNKNOWN"
            
            // Get precision values safely
            val hPrec = try { position.getHPrecision() } catch (e: Exception) { 
                Log.e(TAG, "Error accessing hPrecision: ${e.message}", e)
                Double.NaN 
            }
            val vPrec = try { position.getVPrecision() } catch (e: Exception) { 
                Log.e(TAG, "Error accessing vPrecision: ${e.message}", e)
                Double.NaN 
            }
            val hPrecision = if (hPrec.isNaN() || hPrec.isInfinite()) -1.0 else hPrec
            val vPrecision = if (vPrec.isNaN() || vPrec.isInfinite()) -1.0 else vPrec
            
            // Get DOP values safely
            val pdopValue = try { position.getPdop() } catch (e: Exception) { 
                Log.e(TAG, "Error accessing pdop: ${e.message}", e)
                Double.NaN 
            }
            val hdopValue = try { position.getHdop() } catch (e: Exception) { 
                Log.e(TAG, "Error accessing hdop: ${e.message}", e)
                Double.NaN 
            }
            val vdopValue = try { position.getVdop() } catch (e: Exception) { 
                Log.e(TAG, "Error accessing vdop: ${e.message}", e)
                Double.NaN 
            }
            
            // Calculate receiver health based on position and satellite data
            val receiverHealth = when {
                fixTypeName.contains("INVALID", ignoreCase = true) -> "NO_FIX"
                (fixTypeName.contains("AUTONOMOUS", ignoreCase = true) && latestSatellitesInView < 4) -> "NO_FIX"
                latestSatellitesInView < 4 -> "POOR"
                hPrecision > 2.5 -> "POOR"
                hPrecision > 0 && hPrecision < 1.0 -> "EXCELLENT"
                hPrecision > 0 -> "GOOD"
                else -> "UNKNOWN"
            }
            
            // Calculate overall health
            val health = when {
                (latDegrees == 0.0 && lonDegrees == 0.0) || latDegrees.isNaN() || lonDegrees.isNaN() -> "NO_COORDINATES"
                (fixTypeName.contains("AUTONOMOUS", ignoreCase = true) && latestSatellitesInView < 4) -> "NO_FIX"
                try { latestHealth?.getSensorState()?.toString()?.contains("ERROR", ignoreCase = true) == true } catch (e: Exception) { false } -> "ERROR"
                else -> "OK"
            }
            
            val payload = TelemetryPayload(
                tenantId = tenantId,
                deviceId = deviceId,
                latitude = latDegrees,
                longitude = lonDegrees,
                battery = try { latestBattery?.getBatteryLevel() } catch (e: Exception) { null }
                    ?: DeviceInfoUtil.batteryLevel(context), // Use receiver battery if available, else phone battery
                fixType = fixTypeName,
                timestamp = Instant.now().toString(),
                health = health,
                horizontalAccuracy = hPrecision,
                verticalAccuracy = vPrecision,
                satellites = latestSatellitesInView,
                receiverBattery = try { latestBattery?.getBatteryLevel() } catch (e: Exception) { null }?.takeIf { it in 0..100 },
                pdop = if (pdopValue.isNaN() || pdopValue.isInfinite()) null else pdopValue,
                hdop = if (hdopValue.isNaN() || hdopValue.isInfinite()) null else hdopValue,
                vdop = if (vdopValue.isNaN() || vdopValue.isInfinite()) null else vdopValue,
                receiverHealth = receiverHealth
            )
            
            onMessage(payload)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating telemetry payload", e)
            Log.e(TAG, "Exception details: ${e.message}", e)
            onError(e)
        }
    }

    fun close() {
        try {
            Log.i(TAG, "Closing Catalyst client")
            
            if (sdkConnected && facade != null) {
                try {
                    // Only end survey if one was started
                    facade?.endSurvey()
                } catch (e: Exception) {
                    Log.w(TAG, "Error ending survey (may not be started)", e)
                }
                
                try {
                    facade?.disconnectFromSensor()
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting from sensor", e)
                }
            }
            
            isConnected = false
            sdkConnected = false
            lastDataReceivedAt = null
            currentError = null
            
            try {
                facade?.removeCatalystEventListener(eventListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing event listener", e)
            }
            
            try {
                facade?.releaseDriver()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing driver", e)
            }
            
            facade = null
            
            // Clear cached data
            latestPosition = null
            latestSatellites = null
            latestBattery = null
            latestHealth = null
            latestSatellitesInView = 0

        } catch (e: Exception) {
            Log.e(TAG, "Error closing Catalyst client", e)
        }
    }
}
