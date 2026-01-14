package com.hirenq.tmmrelay.service

import android.content.Context
import android.util.Log
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
import trimble.jssi.interfaces.gnss.PositionRate
import org.json.JSONArray
import org.json.JSONObject
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

     fun connect(tenantId: String, deviceId: String) {
    this.tenantId = tenantId
    this.deviceId = deviceId

    Thread {
        try {
            LogCapture.log(Log.INFO, TAG, "=== Catalyst connect() start ===")

            /* ---------------- Create Facade ---------------- */
            val appGuid = context.packageName
            facade = CatalystFacade(appGuid, context.applicationContext)

            /* ---------------- Load Subscription ---------------- */
            // User must manually log into TMM app first (outside this app)
            // SDK will talk to TMM internally via system services
            LogCapture.log(Log.INFO, TAG, "Loading subscription...")
            facade!!.loadSubscription()
            LogCapture.log(Log.INFO, TAG, "Subscription load() called - SDK will handle the result")

            /* ---------------- Get Sensor Properties ---------------- */
            // Licensing is applied automatically by SDK
            facade!!.getSensorProperties()
            LogCapture.log(Log.INFO, TAG, "getSensorProperties() called - SDK will handle licensing")

            /* ---------------- Init Driver ---------------- */
            val initRc = facade!!.initDriver(DriverType.Catalyst)
            if (initRc.code != DriverReturnCode.Success) {
                LogCapture.log(Log.ERROR, TAG, "Driver init failed: ${initRc.code}")
                currentError = "DRIVER_INIT_FAILED"
                onError(RuntimeException("Driver init failed: ${initRc.code}"))
                return@Thread
            }
            LogCapture.log(Log.INFO, TAG, "Driver initialized successfully")

            /* ---------------- Connect ---------------- */
            val connectRc = facade!!.connect()
            if (connectRc.code != DriverReturnCode.Success) {
                LogCapture.log(Log.ERROR, TAG, "Connect failed: ${connectRc.code}")
                currentError = "CONNECT_FAILED"
                onError(RuntimeException("Connect failed: ${connectRc.code}"))
                return@Thread
            }
            LogCapture.log(Log.INFO, TAG, "Connected to sensor")

            /* ---------------- Listener ---------------- */
            facade!!.addCatalystEventListener(eventListener)

            /* ---------------- Output Rate ---------------- */
            facade!!.setOutputPositionRate(PositionRate.OneHz)

            /* ---------------- Start Survey ---------------- */
            facade!!.startSurvey()
            LogCapture.log(Log.INFO, TAG, "Survey started")

            sdkConnected = true
            LogCapture.log(Log.INFO, TAG, "=== Catalyst SDK connected and survey started ===")

        } catch (e: Exception) {
            LogCapture.log(Log.ERROR, TAG, "Fatal connect error: ${e.message}", e)
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
                receiverHealth = receiverHealth,
                // Mobile GPS data will be added by TmmRelayService
                mobileLatitude = null,
                mobileLongitude = null,
                mobileAccuracy = null,
                mobileBattery = null,
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
                                satObj.put("prn", sat.getPrn())
                                satObj.put("constellation", sat.getConstellation()?.toString())
                                satObj.put("elevation", sat.getElevation() * 180.0 / PI)
                                satObj.put("azimuth", sat.getAzimuth() * 180.0 / PI)
                                satObj.put("snr", sat.getSnr())
                                satObj.put("used", sat.isUsed())
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
