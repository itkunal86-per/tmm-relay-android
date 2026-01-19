package com.hirenq.tmmrelay.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hirenq.tmmrelay.model.TelemetryPayload
import com.hirenq.tmmrelay.util.DeviceInfoUtil
import com.hirenq.tmmrelay.util.LogCapture
import trimble.jssi.android.catalystfacade.CatalystFacade
import trimble.jssi.android.catalystfacade.DriverReturnCode
import trimble.jssi.android.catalystfacade.DriverType
import trimble.jssi.android.catalystfacade.ICatalystEventListener
import trimble.jssi.android.catalystfacade.PositionUpdate
import trimble.jssi.android.catalystfacade.PowerSourceState
import trimble.jssi.android.catalystfacade.ReturnCode
import trimble.jssi.android.catalystfacade.ReturnObject
import trimble.jssi.android.catalystfacade.SatelliteUpdate
import trimble.jssi.android.catalystfacade.SensorProperties
import trimble.jssi.android.catalystfacade.SensorStateEvent
import trimble.jssi.android.catalystfacade.ImuStateEvent
import trimble.jssi.android.catalystfacade.SubscriptionDetails
import trimble.jssi.android.catalystfacade.TargetReferenceFrame
import trimble.jssi.interfaces.gnss.PositionRate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.Properties
import kotlin.math.PI

class CatalystClient(
    private val context: Context,
    private val onMessage: (TelemetryPayload) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    // Device license subscription (alternative to TMM subscription)
    private val deviceLicense: String? = null,   // Device license string - if provided, uses loadDeviceSubscription instead of loadSubscription
    // TMM user TID for subscription loading (alternative to loadSubscription())
    private val userTID: String? = null          // User TID from TMM Login - if provided, uses loadSubscriptionFromTrimbleMobileManager(userTID)
) {
    
    // Config file (matching MainModel.java getConfigFile() line 359-364)
    private val configFile: File by lazy {
        File(context.filesDir.absolutePath + File.separator + "config.properties")
    }

    // TAG for logging - using our class name for clarity
    // Note: CatalystFacade.java uses "JCatalystFacade" as its internal TAG,
    // but we use "CatalystClient" since we're in our own client class for better log filtering
    private val TAG = "CatalystClient"
    
    // Config property keys (matching MainModel.java lines 171-185)
    companion object {
        private const val CONFIG_KEY_DRIVER_TYPE = "DriverType"
        private const val CONFIG_KEY_CONNECTION_TYPE = "ConnectionType"
        private const val CONFIG_KEY_DEVICE_ADDRESS = "DeviceAddress"
        private const val CONFIG_KEY_DEVICE_NAME = "DeviceName"
        private const val CONFIG_KEY_DEVICE_PORT_NO = "DevicePortNo"
        private const val CONFIG_KEY_REDUCED_ANTENNA_HEIGHT = "ReducedAntennaHeight"
    }
    
    // Read configuration from file (matching MainModel.java readConfig() lines 965-991)
    private fun readConfig(): Properties? {
        if (!configFile.exists()) {
            createDefaultConfig()
        }
        if (!configFile.exists()) {
            return null
        }
        LogCapture.log(Log.INFO, TAG, "Reading Configuration from ${configFile.absolutePath}")
        val properties = Properties()
        var fileInputStream: FileInputStream? = null
        try {
            fileInputStream = FileInputStream(configFile)
            properties.load(fileInputStream)
        } catch (e: IOException) {
            LogCapture.log(Log.ERROR, TAG, "Error reading config file: ${e.message}", e)
            return null
        } finally {
            try {
                fileInputStream?.close()
            } catch (e: IOException) {
                LogCapture.log(Log.WARN, TAG, "Error closing config file: ${e.message}", e)
            }
        }
        return properties
    }
    
    // Create default configuration (matching MainModel.java createDefaultConfig() lines 993-1011)
    private fun createDefaultConfig() {
        LogCapture.log(Log.INFO, TAG, "Creating default configuration")
        val properties = Properties()
        properties.setProperty(CONFIG_KEY_DRIVER_TYPE, trimble.jssi.android.catalystfacade.DriverType.TrimbleGNSS.name) // Default to TrimbleGNSS (matching demo line 996)
        properties.setProperty(CONFIG_KEY_CONNECTION_TYPE, "Bluetooth")
        properties.setProperty(CONFIG_KEY_DEVICE_ADDRESS, "")
        properties.setProperty(CONFIG_KEY_DEVICE_NAME, "90:7B:C6:B4:12:30")
        properties.setProperty(CONFIG_KEY_DEVICE_PORT_NO, "")
        properties.setProperty(CONFIG_KEY_REDUCED_ANTENNA_HEIGHT, "2.0")
        writeConfigToFile(properties)
    }
    
    // Write configuration to file (matching MainModel.java writeConfigToFile() lines 1092-1110)
    private fun writeConfigToFile(properties: Properties) {
        var fileOutputStream: FileOutputStream? = null
        try {
            fileOutputStream = FileOutputStream(configFile)
            properties.store(fileOutputStream, "Configuration CatalystFacade")
        } catch (e: IOException) {
            LogCapture.log(Log.ERROR, TAG, "Error writing config file: ${e.message}", e)
        } finally {
            try {
                fileOutputStream?.close()
            } catch (e: IOException) {
                LogCapture.log(Log.WARN, TAG, "Error closing config file: ${e.message}", e)
            }
        }
    }
    
    // Read driver type from config (matching MainModel.java readDriverTypeFromConfig() lines 723-728)
    private fun readDriverTypeFromConfig(): DriverType? {
        val config = readConfig() ?: return null
        return parseDriverType(config)
    }
    
    // Parse driver type from Properties (matching MainModel.java parseDriverType() lines 730-742)
    private fun parseDriverType(config: Properties): DriverType? {
        val deviceTypeStr = config.getProperty(CONFIG_KEY_DRIVER_TYPE)
        if (deviceTypeStr == null) {
            return null
        }
        return try {
            trimble.jssi.android.catalystfacade.DriverType.valueOf(deviceTypeStr)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    
    // Get driver type with default (matching MainModel.java getDriverType() lines 577-585)
    private fun getDriverType(deviceTypeStr: String?): DriverType {
        if (deviceTypeStr == null) {
            return trimble.jssi.android.catalystfacade.DriverType.TrimbleGNSS // Default (matching demo line 582)
        }
        return try {
            trimble.jssi.android.catalystfacade.DriverType.valueOf(deviceTypeStr)
        } catch (e: IllegalArgumentException) {
            trimble.jssi.android.catalystfacade.DriverType.TrimbleGNSS // Default if invalid (matching demo line 582)
        }
    }
    
    // Validate connection configuration based on driver type (matching Configuration.java logic)
    private fun validateConnectionConfig(driverType: DriverType, connectionType: String?, deviceAddress: String?, devicePortNo: String?): Boolean {
        // TrimbleGNSS and SpectraPrecision require connection configuration
        if (driverType == trimble.jssi.android.catalystfacade.DriverType.TrimbleGNSS || driverType == trimble.jssi.android.catalystfacade.DriverType.SpectraPrecision) {
            if (connectionType == null || deviceAddress == null) {
                LogCapture.log(Log.ERROR, TAG, "TrimbleGNSS/SpectraPrecision drivers require connection configuration (ConnectionType, DeviceAddress)")
                return false
            }
            
            // Validate connection type based on driver type (matching updateConnectionTypes logic)
            when (connectionType) {
                "Bluetooth" -> {
                    // Bluetooth is valid for both TrimbleGNSS and SpectraPrecision
                    if (deviceAddress.isBlank()) {
                        LogCapture.log(Log.ERROR, TAG, "Bluetooth connection requires DeviceAddress")
                        return false
                    }
                }
                "TcpIp" -> {
                    // TcpIp is only valid for TrimbleGNSS (not SpectraPrecision)
                    if (driverType == trimble.jssi.android.catalystfacade.DriverType.SpectraPrecision) {
                        LogCapture.log(Log.ERROR, TAG, "TcpIp connection is only supported for TrimbleGNSS, not SpectraPrecision")
                        return false
                    }
                    if (deviceAddress.isBlank() || devicePortNo == null || devicePortNo.isBlank()) {
                        LogCapture.log(Log.ERROR, TAG, "TcpIp connection requires DeviceAddress and DevicePortNo")
                        return false
                    }
                }
                else -> {
                    LogCapture.log(Log.ERROR, TAG, "Invalid ConnectionType: $connectionType. Must be 'Bluetooth' or 'TcpIp'")
                    return false
                }
            }
        }
        return true
    }
    
    // Check if Bluetooth permissions are granted (required for Bluetooth-enabled drivers)
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires BLUETOOTH_CONNECT and BLUETOOTH_SCAN
            val connectGranted = ContextCompat.checkSelfPermission(
                context, 
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val scanGranted = ContextCompat.checkSelfPermission(
                context, 
                android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            connectGranted && scanGranted
        } else {
            // Android 11 and below - Bluetooth permissions are granted at install time
            true
        }
    }
    
    // Start positioning explicitly (matching CatalystFacade.java subscribe() method lines 809-813)
    private fun startPositioning() {
        try {
            // Use reflection to access the internal sensor object from CatalystFacade
            // This ensures positioning is started even if internal subscribe() wasn't called
            val facadeClass = facade?.javaClass
            val sensorField = facadeClass?.getDeclaredField("sensor")
            sensorField?.isAccessible = true
            val sensor = sensorField?.get(facade)
            
            if (sensor == null) {
                LogCapture.log(Log.WARN, TAG, "Sensor is null - positioning may already be started by CatalystFacade")
                return
            }
            
            // Get ISsiPositioning interface (matching CatalystFacade.java line 809)
            val getInterfaceMethod = sensor.javaClass.getMethod("getInterface", Class.forName("trimble.jssi.interfaces.SsiInterfaceType"))
            val ssiPositioningType = Class.forName("trimble.jssi.interfaces.SsiInterfaceType")
            val positioningTypeEnum = ssiPositioningType.getField("SsiPositioning").get(null)
            
            val ssiPositioning = getInterfaceMethod.invoke(sensor, positioningTypeEnum)
            
            if (ssiPositioning != null) {
                // Create PositioningSettings (matching CatalystFacade.java line 812)
                val positioningSettingsClass = Class.forName("trimble.jssi.interfaces.gnss.positioning.PositioningSettings")
                val positioningSettings = positioningSettingsClass.getDeclaredConstructor().newInstance()
                
                // Start positioning (matching CatalystFacade.java line 812)
                val startPositioningMethod = ssiPositioning.javaClass.getMethod("startPositioning", positioningSettingsClass)
                startPositioningMethod.invoke(ssiPositioning, positioningSettings)
                
                LogCapture.log(Log.INFO, TAG, "✅ Positioning started successfully via reflection")
            } else {
                LogCapture.log(Log.WARN, TAG, "ISsiPositioning interface not available - positioning may already be started")
            }
        } catch (e: Exception) {
            // If reflection fails, positioning may already be started by CatalystFacade internally
            LogCapture.log(Log.WARN, TAG, "Could not start positioning via reflection (may already be started): ${e.message}")
            LogCapture.log(Log.DEBUG, TAG, "Positioning should be started automatically by CatalystFacade after connection")
        }
    }
    
    // Set reduced antenna height from config (matching MainModel.java setReducedAntennaHeight() lines 678-693)
    private fun setReducedAntennaHeight() {
        try {
            val config = readConfig()
            val reducedAntennaHeightStr = config?.getProperty(CONFIG_KEY_REDUCED_ANTENNA_HEIGHT)
            
            if (config != null && reducedAntennaHeightStr != null) {
                var reducedAntennaHeight = 2.0
                
                if (reducedAntennaHeightStr.isNotEmpty()) {
                    try {
                        reducedAntennaHeight = reducedAntennaHeightStr.toDouble()
                    } catch (e: NumberFormatException) {
                        LogCapture.log(Log.WARN, TAG, "Invalid ReducedAntennaHeight value in config: $reducedAntennaHeightStr")
                        return
                    }
                }
                
                LogCapture.log(Log.INFO, TAG, "Setting reduced antenna height: $reducedAntennaHeight")
                facade?.setReducedAntennaHeight(reducedAntennaHeight)
                LogCapture.log(Log.INFO, TAG, "✅ Set reduced antenna height success")
            } else {
                LogCapture.log(Log.DEBUG, TAG, "ReducedAntennaHeight not configured, using default (0.0)")
            }
        } catch (e: Exception) {
            // Ignoring exceptions (matching demo behavior)
            LogCapture.log(Log.WARN, TAG, "Error setting reduced antenna height: ${e.message}")
        }
    }
    
    private var facade: CatalystFacade? = null
    private var tenantId: String = ""
    private var deviceId: String = ""
    private var isConnected = false
    private var currentError: String? = null
    private var lastDataReceivedAt: Instant? = null
    private var sdkConnected = false // Track SDK connection separately from actual data reception
    
    fun getConnectionStatus(): Boolean {
        // Only consider connected if we've received data recently (within last 30 seconds)
        //return if (lastDataReceivedAt != null) {
         //   val secondsSinceLastData = java.time.Duration.between(lastDataReceivedAt, Instant.now()).seconds
           // sdkConnected && secondsSinceLastData < 30
        //} else {
       //     false
       // }

       return sdkConnected
    }
    fun isGnssActive(): Boolean {
         return lastDataReceivedAt != null &&
           java.time.Duration.between(lastDataReceivedAt, Instant.now()).seconds < 30
    }
    fun getCurrentError(): String? = currentError
    
    /**
     * Get power source state directly from the sensor (matching CatalystFacade.java getPowerSourceState)
     * This queries the current power state rather than waiting for an event update
     * @return PowerSourceState or null if sensor is not connected or power info not available
     */
    fun getPowerSourceState(): PowerSourceState? {
        return try {
            facade?.getPowerSourceState()
        } catch (e: Exception) {
            LogCapture.log(Log.WARN, TAG, "Error getting power source state: ${e.message}", e)
            null
        }
    }
    
   
    
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
                
                Log.i(TAG, "Position update received: lat=$latDegrees, lon=$lonDegrees, acc=$hPrec, fix=$solution")
                // Always call createAndSendTelemetry even if coordinates are 0.0 initially
                // This ensures diagnostics are broadcast and UI is updated
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
                latestPosition?.let { this@CatalystClient.createAndSendTelemetry() }
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
            LogCapture.log(Log.ERROR, TAG, "USB connection error occurred")
            currentError = "USB_CONNECTION_ERROR"
            isConnected = false
            sdkConnected = false
            lastDataReceivedAt = null
            onError(RuntimeException("USB connection error"))
        }

        override fun onSubscriptionHasExpired() {
            LogCapture.log(Log.ERROR, TAG, "Subscription has expired")
            currentError = "NO_SUBSCRIPTION"
            isConnected = false
            sdkConnected = false
            lastDataReceivedAt = null
            onError(RuntimeException("Subscription has expired"))
        }
    }

    private fun logReturn(action: String, result: Any?): Boolean {
    return when (result)
     {

        is ReturnObject<*> -> {
            if (result.code != DriverReturnCode.Success) {
                LogCapture.log(
                    Log.ERROR,
                    TAG,
                    "❌ $action failed → ${result.code}"
                )
                false
            } else {
                LogCapture.log(
                    Log.INFO,
                    TAG,
                    "✅ $action success"
                )
                true
            }
        }

        is DriverReturnCode -> {
            if (result != DriverReturnCode.Success) {
                LogCapture.log(
                    Log.ERROR,
                    TAG,
                    "❌ $action failed → $result"
                )
                false
            } else {
                LogCapture.log(
                    Log.INFO,
                    TAG,
                    "✅ $action success"
                )
                true
            }
        }

        else -> {
            LogCapture.log(
                Log.WARN,
                TAG,
                "⚠ $action returned unknown result type"
            )
            true
        }
     }
    }


    fun connect(tenantId: String, deviceId: String) {
        this.tenantId = tenantId
        this.deviceId = deviceId

        Thread {
            try {
                LogCapture.log(Log.INFO, TAG, "=== Catalyst connect() start ===")

                /* ---------------- Step 1: Create Facade ---------------- */
                val appGuid = context.packageName
                facade = CatalystFacade(appGuid, context.applicationContext)
                LogCapture.log(Log.INFO, TAG, "✅ Facade created")

                /* ---------------- Step 2: Load Subscription ---------------- */
                // Support three subscription types (matching demo MainModel.java):
                // 1. Device license subscription (V2 licensing) - highest priority
                // 2. TMM user subscription with userTID (loadSubscriptionFromTrimbleMobileManager)
                // 3. Default TMM subscription (loadSubscription) - fallback
                val loadSubRc = when {
                    deviceLicense != null && deviceLicense.isNotEmpty() -> {
                        // Use device license subscription (V2 licensing)
                        LogCapture.log(Log.INFO, TAG, "Loading device license subscription...")
                        val loadSubResult = facade!!.loadDeviceSubscription(deviceLicense)
                        if (loadSubResult.code == DriverReturnCode.Success) {
                            val subscriptionDetails = loadSubResult.returnedObject
                            LogCapture.log(Log.INFO, TAG, "✅ Load device subscription success")
                            LogCapture.log(Log.INFO, TAG, "Subscription: ${subscriptionDetails.subscriptionName}")
                            LogCapture.log(Log.INFO, TAG, "Issue Date: ${subscriptionDetails.issueDate}")
                            LogCapture.log(Log.INFO, TAG, "Expiry Date: ${subscriptionDetails.expiryDate}")
                        }
                        loadSubResult.code
                    }
                    userTID != null && userTID.isNotEmpty() -> {
                        // Use TMM subscription with userTID (matching demo MainModel.java line 508)
                        LogCapture.log(Log.INFO, TAG, "Loading subscription from TMM with userTID: $userTID...")
                        val retCode = facade!!.loadSubscriptionFromTrimbleMobileManager(userTID)
                        if (retCode.code == DriverReturnCode.Success) {
                            LogCapture.log(Log.INFO, TAG, "✅ Load subscription from TMM success (userTID: $userTID)")
                        }
                        retCode.code
                    }
                    else -> {
                        // Use default TMM subscription (fallback)
                        LogCapture.log(Log.INFO, TAG, "Loading subscription from TMM (default)...")
                        val retCode = facade!!.loadSubscription()
                        if (retCode.code == DriverReturnCode.Success) {
                            LogCapture.log(Log.INFO, TAG, "✅ Load subscription success")
                        }
                        retCode.code
                    }
                }
                
                if (loadSubRc != DriverReturnCode.Success) {
                    LogCapture.log(Log.ERROR, TAG, "❌ Load subscription failed: $loadSubRc")
                    currentError = "NO_SUBSCRIPTION"
                    onError(RuntimeException("Load subscription failed: $loadSubRc"))
                    return@Thread
                }

                /* ---------------- Step 3: Read Config and Driver Type ---------------- */
                // Read configuration from file (matching MainModel.java line 596)
                val config = readConfig()
                if (config == null) {
                    LogCapture.log(Log.ERROR, TAG, "❌ Unable to read configuration")
                    currentError = "CONFIG_ERROR"
                    onError(RuntimeException("Unable to read configuration"))
                    return@Thread
                }
                
                // Read driver type from config (matching MainModel.java line 598)
                // In demo: DriverType driverType = readDriverTypeFromConfig();
                // We get it from config directly since we already have it
                val deviceTypeStr = config.getProperty(CONFIG_KEY_DRIVER_TYPE)
                val driverType = getDriverType(deviceTypeStr) // Defaults to TrimbleGNSS if null or invalid (matching demo line 582)
                
                LogCapture.log(Log.INFO, TAG, "Driver type from config: $driverType (config value: ${deviceTypeStr ?: "null"})")

                /* ---------------- Step 4: Init Driver ---------------- */
                // initDriver internally calls releaseDriver() if a driver already exists
                // (matching CatalystFacade.java lines 393-394)
                LogCapture.log(Log.INFO, TAG, "Initializing driver: $driverType...")
                LogCapture.log(Log.DEBUG, TAG, "Note: initDriver will automatically release existing driver if present")
                
                // Check Bluetooth permissions for Bluetooth-enabled drivers BEFORE init
                val needsBluetooth = driverType == trimble.jssi.android.catalystfacade.DriverType.TrimbleGNSS || 
                                    driverType == trimble.jssi.android.catalystfacade.DriverType.SpectraPrecision ||
                                    driverType == trimble.jssi.android.catalystfacade.DriverType.EM100
                if (needsBluetooth && !hasBluetoothPermissions()) {
                    val errorMsg = "Bluetooth permissions required for driver: $driverType. Please grant BLUETOOTH_CONNECT and BLUETOOTH_SCAN permissions."
                    LogCapture.log(Log.ERROR, TAG, "❌ $errorMsg")
                    currentError = "DRIVER_INIT_FAILED"
                    onError(RuntimeException(errorMsg))
                    return@Thread
                }
                
                val initRc = facade!!.initDriver(driverType)
                if (initRc.code != DriverReturnCode.Success) {
                    val errorMsg = "Driver init failed: ${initRc.code} for driver type: $driverType"
                    LogCapture.log(Log.ERROR, TAG, "❌ $errorMsg")
                    LogCapture.log(Log.ERROR, TAG, "Driver type: $driverType")
                    LogCapture.log(Log.ERROR, TAG, "Return code: ${initRc.code}")
                    if (needsBluetooth) {
                        LogCapture.log(Log.ERROR, TAG, "Note: This driver requires Bluetooth. Ensure:")
                        LogCapture.log(Log.ERROR, TAG, "  1. Bluetooth is enabled on device")
                        LogCapture.log(Log.ERROR, TAG, "  2. BLUETOOTH_CONNECT and BLUETOOTH_SCAN permissions are granted")
                        LogCapture.log(Log.ERROR, TAG, "  3. DeviceAddress is configured in config.properties for TrimbleGNSS/SpectraPrecision")
                    }
                    currentError = "DRIVER_INIT_FAILED"
                    onError(RuntimeException(errorMsg))
                    return@Thread
                } else {
                    LogCapture.log(Log.INFO, TAG, "✅ Driver init success: $driverType")
                    // Log driver mapping info (matching CatalystFacade.java deviceTypeMap lines 412-423)
                    // and getDriver() switch statement (lines 428-446)
                    when (driverType) {
                        trimble.jssi.android.catalystfacade.DriverType.TrimbleGNSS, 
                        trimble.jssi.android.catalystfacade.DriverType.EM100 -> {
                            LogCapture.log(Log.INFO, TAG, "Driver loaded: Trimble.Ssi.Driver.CarpoBased.Driver.RSeries")
                            LogCapture.log(Log.INFO, TAG, "License name: TrimbleRSeries")
                        }
                        trimble.jssi.android.catalystfacade.DriverType.Catalyst -> {
                            LogCapture.log(Log.INFO, TAG, "Driver registered: CatalystDriver (via registerDriver)")
                            LogCapture.log(Log.INFO, TAG, "License name: TrimbleCatalyst")
                        }
                        trimble.jssi.android.catalystfacade.DriverType.Mock -> {
                            LogCapture.log(Log.INFO, TAG, "Driver loaded: Trimble.Ssi.Driver.Mock.GNSS")
                            LogCapture.log(Log.INFO, TAG, "License name: TrimbleMockGNSS")
                        }
                        trimble.jssi.android.catalystfacade.DriverType.SpectraPrecision, 
                        trimble.jssi.android.catalystfacade.DriverType.TDC150 -> {
                            LogCapture.log(Log.INFO, TAG, "Driver loaded: Trimble.Ssi.Driver.CarpoBased.Driver.SP80")
                            LogCapture.log(Log.INFO, TAG, "License name: SpectraPrecisionGNSS")
                        }
                        else -> {
                            LogCapture.log(Log.WARN, TAG, "Unknown driver type: $driverType")
                        }
                    }
                }

                /* ---------------- Step 5: Read Connection Config from Config File ---------------- */
                // Read connection configuration from config file (matching MainModel.java line 596)
                val connectionType = config.getProperty(CONFIG_KEY_CONNECTION_TYPE)
                val deviceAddress = "90:7B:C6:B4:12:30"
                //config.getProperty(CONFIG_KEY_DEVICE_ADDRESS)
                val devicePortNo = config.getProperty(CONFIG_KEY_DEVICE_PORT_NO)
                
           
                /* ---------------- Step 6: Connect ---------------- */
                LogCapture.log(Log.INFO, TAG, "Connecting to sensor using driver: $driverType...")
                var retCode: ReturnCode = ReturnCode(DriverReturnCode.Error)
                
                // Handle different driver types matching MainModel.java pattern using when statement (lines 603-627)
                when (driverType) {
                    trimble.jssi.android.catalystfacade.DriverType.Catalyst,
                    trimble.jssi.android.catalystfacade.DriverType.EM100,
                    trimble.jssi.android.catalystfacade.DriverType.TDC150 -> {
                        LogCapture.log(Log.INFO, TAG, "Connecting via standard connection (Catalyst/EM100/TDC150)...")
                        retCode = facade!!.connect()
                    }
                    
                    trimble.jssi.android.catalystfacade.DriverType.TrimbleGNSS,
                    trimble.jssi.android.catalystfacade.DriverType.SpectraPrecision -> {
                        // Validate connection configuration (matching Configuration.java updateConnectionTypes logic)
                        // - TrimbleGNSS: supports both Bluetooth and TcpIp
                        // - SpectraPrecision: supports only Bluetooth (not TcpIp)
                        if (!validateConnectionConfig(driverType, connectionType, deviceAddress, devicePortNo)) {
                            currentError = "CONNECT_FAILED"
                            onError(RuntimeException("Invalid connection configuration for driver type $driverType"))
                            return@Thread
                        }
                        
                        // Handle connection based on ConnectionType (matching MainModel.java lines 615-623)
                        // At this point, validation ensures:
                        // - TrimbleGNSS: Bluetooth or TcpIp (both valid)
                        // - SpectraPrecision: Bluetooth only (TcpIp rejected by validateConnectionConfig)
                        retCode = when (connectionType) {
                            "Bluetooth" -> {
                                // Double-check Bluetooth permissions before connecting
                                if (!hasBluetoothPermissions()) {
                                    val errorMsg = "Bluetooth permissions not granted. Please grant BLUETOOTH_CONNECT and BLUETOOTH_SCAN permissions."
                                    LogCapture.log(Log.ERROR, TAG, "❌ $errorMsg")
                                    currentError = "CONNECT_FAILED"
                                    onError(RuntimeException(errorMsg))
                                    return@Thread
                                }
                                
                                if (deviceAddress.isNullOrBlank()) {
                                    val errorMsg = "Bluetooth connection requires DeviceAddress in config.properties. Example: DeviceAddress=00:11:22:33:44:55"
                                    LogCapture.log(Log.ERROR, TAG, "❌ $errorMsg")
                                    currentError = "CONNECT_FAILED"
                                    onError(RuntimeException(errorMsg))
                                    return@Thread
                                }
                                
                                LogCapture.log(Log.INFO, TAG, "Connecting via Bluetooth to address: $deviceAddress")
                                LogCapture.log(Log.DEBUG, TAG, "Driver type: $driverType, Bluetooth address: $deviceAddress")
                                facade!!.connectViaBluetooth(deviceAddress)
                            }
                            "TcpIp" -> {
                                // TcpIp is only valid for TrimbleGNSS (validated in validateConnectionConfig)
                                // SpectraPrecision cannot reach here due to validation
                                LogCapture.log(Log.INFO, TAG, "Connecting via WiFi/TcpIp to $deviceAddress:$devicePortNo")
                                facade!!.connectViaWifi(deviceAddress ?: "", devicePortNo ?: "")
                            }
                            else -> {
                                LogCapture.log(Log.ERROR, TAG, "Invalid ConnectionType: $connectionType. Must be 'Bluetooth' or 'TcpIp'")
                                currentError = "CONNECT_FAILED"
                                onError(RuntimeException("Invalid ConnectionType: $connectionType. Must be 'Bluetooth' or 'TcpIp'"))
                                return@Thread
                            }
                        }
                    }
                    
                    trimble.jssi.android.catalystfacade.DriverType.Mock -> {
                        LogCapture.log(Log.INFO, TAG, "Connecting via Mock driver...")
                        retCode = facade!!.connectMock()
                    }
                    
                    else -> {
                        LogCapture.log(Log.ERROR, TAG, "Unsupported driver type: $driverType")
                        currentError = "CONNECT_FAILED"
                        onError(RuntimeException("Unsupported driver type: $driverType"))
                        return@Thread
                    }
                }
                
               

                /* 🔴 REQUIRED IN 2025.12.5 — ADD LISTENER IMMEDIATELY */
                LogCapture.log(Log.INFO, TAG, "Adding event listener (early)...")
                facade!!.addCatalystEventListener(eventListener)

                /* Give IPC a moment */
                Thread.sleep(300)

                /* ---------------- Step 7: Set Reduced Antenna Height (matching demo MainModel.java line 656-658) ---------------- */
                // Only call setReducedAntennaHeight if connect was successful (matching demo line 656)
                if (retCode.getCode() == DriverReturnCode.Success) {
                    setReducedAntennaHeight()
                } else {
                    LogCapture.log(Log.WARN, TAG, "Skipping setReducedAntennaHeight - connect failed with code: ${retCode.getCode()}")
                }

                /* ---------------- Step 8: Set Output Position Rate (matching demo MainModel.java line 662-665) ---------------- */
                LogCapture.log(Log.INFO, TAG, "Setting output position rate...")
                val returnCode = facade!!.setOutputPositionRate(PositionRate.OneHz)
                if (returnCode.getCode() != DriverReturnCode.Success) {
                    LogCapture.log(Log.ERROR, TAG, "❌ Set output position rate failed: ${returnCode.getCode()}")
                } else {
                    LogCapture.log(Log.INFO, TAG, "✅ Set output position rate success")
                }

                /* ---------------- Step 9: Start Positioning (matching CatalystFacade.java lines 809-813) ---------------- */
                // Explicitly start positioning to ensure we receive position updates
                startPositioning()

                sdkConnected = true
                LogCapture.log(Log.INFO, TAG, "=== Catalyst SDK connected successfully ===")

            } catch (e: Exception) {
                LogCapture.log(Log.ERROR, TAG, "❌ Fatal connect error: ${e.message}", e)
                currentError = "INIT_FAILED"
                onError(e)
            }
        }.start()
    }
    
    
    fun createAndSendTelemetry() {
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
            
            // Build full survey data JSON array from PositionUpdate
            val surveyDataArray = buildSurveyDataArray(position)
            
            // Get DA2 receiver battery (nullable)
            val da2Battery = try { latestBattery?.getBatteryLevel() } catch (e: Exception) { null }?.takeIf { it in 0..100 }
            
            // Use coordinates even if they're 0.0 initially - they may become valid on subsequent updates
            // Only filter out NaN and Infinite values
            val validLat = if (!latDegrees.isNaN() && !latDegrees.isInfinite()) latDegrees else 0.0
            val validLon = if (!lonDegrees.isNaN() && !lonDegrees.isInfinite()) lonDegrees else 0.0
            
            Log.d(TAG, "Creating telemetry payload: lat=$validLat, lon=$validLon, fixType=$fixTypeName")
            
            val payload = TelemetryPayload(
                tenantId = tenantId,
                // DA2 receiver data (nullable) - fields 5-17
                deviceId = null, // DA2 receiver device ID (not available yet, can be set later if needed)
                latitude = validLat, // DA2 coordinates (may be 0.0 initially)
                longitude = validLon, // DA2 coordinates (may be 0.0 initially)
                battery = da2Battery, // DA2 receiver battery
                fixType = fixTypeName, // DA2 fix type
                timestamp = Instant.now().toString(), // DA2 timestamp
                health = health, // DA2 health
                horizontalAccuracy = if (hPrecision >= 0 && !hPrecision.isNaN() && !hPrecision.isInfinite()) hPrecision else 0.0, // DA2 horizontal accuracy
                verticalAccuracy = if (vPrecision >= 0 && !vPrecision.isNaN() && !vPrecision.isInfinite()) vPrecision else 0.0, // DA2 vertical accuracy
                satellites = if (latestSatellitesInView >= 0) latestSatellitesInView else null, // DA2 satellites
                userId = null, // DA2 user data (not available)
                userName = null, // DA2 user data (not available)
                userEmail = null, // DA2 user data (not available)
                // Mobile GPS data (always included) - will be enriched by TmmRelayService
                mobileDeviceId = deviceId, // Mobile device ID (always present)
                mobileLatitude = null, // Will be added by TmmRelayService
                mobileLongitude = null, // Will be added by TmmRelayService
                mobileAccuracy = null, // Will be added by TmmRelayService
                mobileBattery = null, // Will be added by TmmRelayService
                dataSource = "TRIMBLE", // This payload is from Trimble receiver
                surveyData = surveyDataArray
            )
            
            onMessage(payload)
            
        } catch (e: Exception) {
            LogCapture.log(Log.ERROR, TAG, "Error creating telemetry payload", e)
            LogCapture.log(Log.ERROR, TAG, "Exception details: ${e.message}", e)
            onError(e)
        }
    }
    
    private fun buildSurveyDataArray(position: PositionUpdate): String {
        return try {
            val surveyObj = JSONObject()
            
            // Basic position data
            try { surveyObj.put("latitude", position.getLatitude() * 180.0 / PI) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting latitude for survey data: ${e.message}") }
            try { surveyObj.put("longitude", position.getLongitude() * 180.0 / PI) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting longitude for survey data: ${e.message}") }
            try { surveyObj.put("height", position.getHeight()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting height: ${e.message}") }
            try { surveyObj.put("elevation", position.getElevation()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting elevation: ${e.message}") }
            
            // Solution and fix data
            try { surveyObj.put("solution", position.getSolution()?.toString()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting solution: ${e.message}") }
            try { surveyObj.put("groundPositionType", position.getGroundPositionType()?.toString()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting groundPositionType: ${e.message}") }
            
            // Precision and accuracy
            try { surveyObj.put("hPrecision", position.getHPrecision()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting hPrecision: ${e.message}") }
            try { surveyObj.put("vPrecision", position.getVPrecision()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting vPrecision: ${e.message}") }
            try { surveyObj.put("sigmaSemiMajorAxis", position.getSigmaSemiMajorAxis()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting sigmaSemiMajorAxis: ${e.message}") }
            try { surveyObj.put("sigmaSemiMinorAxis", position.getSigmaSemiMinorAxis()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting sigmaSemiMinorAxis: ${e.message}") }
            try { surveyObj.put("sigmaOrientation", position.getSigmaOrientation() * 180.0 / PI) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting sigmaOrientation: ${e.message}") }
            
            // DOP values
            try { surveyObj.put("pdop", position.getPdop()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting pdop: ${e.message}") }
            try { surveyObj.put("hdop", position.getHdop()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting hdop: ${e.message}") }
            try { surveyObj.put("vdop", position.getVdop()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting vdop: ${e.message}") }
            
            // IMU and orientation data
            try { surveyObj.put("imuState", position.getInertialMeasurementUnitState()?.toString()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting imuState: ${e.message}") }
            try { surveyObj.put("pitch", position.getPitch() * 180.0 / PI) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting pitch: ${e.message}") }
            try { surveyObj.put("roll", position.getRoll() * 180.0 / PI) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting roll: ${e.message}") }
            try { surveyObj.put("yaw", position.getYaw() * 180.0 / PI) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting yaw: ${e.message}") }
            try { surveyObj.put("pitchPrecision", position.getPitchPrecision() * 180.0 / PI) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting pitchPrecision: ${e.message}") }
            try { surveyObj.put("rollPrecision", position.getRollPrecision() * 180.0 / PI) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting rollPrecision: ${e.message}") }
            try { surveyObj.put("yawPrecision", position.getYawPrecision() * 180.0 / PI) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting yawPrecision: ${e.message}") }
            
            // Satellite data
            try { surveyObj.put("numberSatellites", position.getNumberSatellites()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting numberSatellites: ${e.message}") }
            try { surveyObj.put("numberTrackedSatellites", position.getNumberTrackedSatellites()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting numberTrackedSatellites: ${e.message}") }
            try { surveyObj.put("satellitesInView", latestSatellitesInView) } catch (e: Exception) {}
            
            // Correction and RTK data
            try { surveyObj.put("staticEpochs", position.getStaticEpochs()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting staticEpochs: ${e.message}") }
            try { surveyObj.put("correctionAge", position.getCorrectionAge()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting correctionAge: ${e.message}") }
            try { surveyObj.put("receivedCorrectionData", position.getReceivedCorrectionData()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting receivedCorrectionData: ${e.message}") }
            try { surveyObj.put("stationId", position.getStationId()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting stationId: ${e.message}") }
            
            // Reference frame data
            try { surveyObj.put("datumTransformationApplied", position.getDatumTransformationApplied()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting datumTransformationApplied: ${e.message}") }
            try { 
                val refFrame = position.getReferenceFrame()
                if (refFrame != null) {
                    surveyObj.put("referenceFrame", refFrame.toString())
                }
            } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting referenceFrame: ${e.message}") }
            try { 
                val sourceRefFrame = position.getSourceReferenceFrame()
                if (sourceRefFrame != null) {
                    surveyObj.put("sourceReferenceFrame", sourceRefFrame.toString())
                }
            } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting sourceReferenceFrame: ${e.message}") }
            
            // Time data
            try { surveyObj.put("gpsTime", position.getGpsTime()?.toString()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting gpsTime: ${e.message}") }
            try { surveyObj.put("utcTime", position.getUtcTime()?.toString()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting utcTime: ${e.message}") }
            
            // Geoid model
            try { surveyObj.put("geoidModel", position.getGeoidModel()) } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting geoidModel: ${e.message}") }
            
            // Battery data if available
            try {
                latestBattery?.let {
                    surveyObj.put("receiverBatteryLevel", it.getBatteryLevel())
                    surveyObj.put("receiverCharging", it.isCharging())
                }
            } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting battery data: ${e.message}") }
            
            // Sensor health if available
            try {
                latestHealth?.let {
                    surveyObj.put("sensorState", it.getSensorState()?.toString())
                }
            } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error getting sensor state: ${e.message}") }
            
            // Satellite details if available
            try {
                latestSatellites?.let { satUpdate ->
                    val satellitesArray = JSONArray()
                    try {
                        val satellites = satUpdate.getSatellites()
                        satellites.forEach { sat ->
                            try {
                                val satObj = JSONObject()
                                // Use available methods from ISatellite interface
                                try { satObj.put("satelliteTypeChar", sat.getSatelliteTypeChar().toString()) } catch (e: Exception) {}
                                try { satObj.put("enabled", sat.getEnabled()) } catch (e: Exception) {}
                                try { satObj.put("used", sat.getUsed()) } catch (e: Exception) {}
                                // Try to get additional properties using reflection for methods that may exist
                                try { 
                                    val elevationMethod = sat.javaClass.getMethod("getElevation")
                                    val elevation = elevationMethod.invoke(sat) as? Double
                                    if (elevation != null) satObj.put("elevation", elevation * 180.0 / PI)
                                } catch (e: Exception) {}
                                try {
                                    val azimuthMethod = sat.javaClass.getMethod("getAzimuth")
                                    val azimuth = azimuthMethod.invoke(sat) as? Double
                                    if (azimuth != null) satObj.put("azimuth", azimuth * 180.0 / PI)
                                } catch (e: Exception) {}
                                try { 
                                    val prnMethod = sat.javaClass.getMethod("getPrn")
                                    satObj.put("prn", prnMethod.invoke(sat))
                                } catch (e: Exception) {}
                                try {
                                    val constMethod = sat.javaClass.getMethod("getConstellation")
                                    val constellation = constMethod.invoke(sat)
                                    if (constellation != null) satObj.put("constellation", constellation.toString())
                                } catch (e: Exception) {}
                                try {
                                    val snrMethod = sat.javaClass.getMethod("getSnr")
                                    satObj.put("snr", snrMethod.invoke(sat))
                                } catch (e: Exception) {}
                                satellitesArray.put(satObj)
                            } catch (e: Exception) {
                                LogCapture.log(Log.WARN, TAG, "Error adding satellite to array: ${e.message}")
                            }
                        }
                        surveyObj.put("satellites", satellitesArray)
                    } catch (e: Exception) {
                        LogCapture.log(Log.WARN, TAG, "Error getting satellites list: ${e.message}")
                    }
                }
            } catch (e: Exception) { LogCapture.log(Log.WARN, TAG, "Error processing satellite update: ${e.message}") }
            
            // Return as JSON array string (wrapping the object in an array)
            JSONArray().apply { put(surveyObj) }.toString()
        } catch (e: Exception) {
            LogCapture.log(Log.ERROR, TAG, "Error building survey data array: ${e.message}", e)
            "[]" // Return empty array on error
        }
    }

    fun close() {
        try {
            LogCapture.log(Log.INFO, TAG, "=== Closing Catalyst client ===")
            
            // Proper cleanup following CatalystFacade.java pattern (lines 362-384)
            // Order: Remove listener -> Disconnect -> Release driver
            
            try {
                // Step 1: Remove event listener first
                facade?.removeCatalystEventListener(eventListener)
                LogCapture.log(Log.INFO, TAG, "✅ Event listener removed")
            } catch (e: Exception) {
                LogCapture.log(Log.WARN, TAG, "⚠ Error removing event listener: ${e.message}", e)
            }
            
            try {
                // Step 2: Disconnect from sensor if connected
                // releaseDriver() internally calls disconnectFromSensor() if connected,
                // but we can also call it explicitly here for clarity
                if (sdkConnected) {
                    facade?.disconnectFromSensor()
                    LogCapture.log(Log.INFO, TAG, "✅ Disconnected from sensor")
                }
            } catch (e: Exception) {
                LogCapture.log(Log.WARN, TAG, "⚠ Error disconnecting from sensor: ${e.message}", e)
            }
            
            try {
                // Step 3: Release driver (matching CatalystFacade.java releaseDriver() at line 362)
                // This internally disconnects if connected and sets driver to null
                val releaseRc = facade?.releaseDriver()
                if (releaseRc != null) {
                    if (releaseRc.code == DriverReturnCode.Success) {
                        LogCapture.log(Log.INFO, TAG, "✅ Driver released successfully")
                    } else if (releaseRc.code == DriverReturnCode.Error) {
                        // Error is expected if driver is already null
                        LogCapture.log(Log.DEBUG, TAG, "Driver release returned Error (driver may already be null)")
                    } else {
                        LogCapture.log(Log.WARN, TAG, "⚠ Driver release returned: ${releaseRc.code}")
                    }
                }
            } catch (e: Exception) {
                LogCapture.log(Log.WARN, TAG, "⚠ Error releasing driver: ${e.message}", e)
            }
            
            // Clear state flags
            isConnected = false
            sdkConnected = false
            lastDataReceivedAt = null
            currentError = null
            
            // Clear facade reference
            facade = null
            
            LogCapture.log(Log.INFO, TAG, "=== Catalyst client closed ===")
            
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
