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
    
    // Track latest values from different event types
    private var latestPosition: PositionUpdate? = null
    private var latestSatellites: SatelliteUpdate? = null
    private var latestSatellitesInView: Int = 0
    private var latestBattery: PowerSourceState? = null
    private var latestHealth: SensorStateEvent? = null

    private val eventListener = object : ICatalystEventListener {
        override fun onPositionUpdate(positionUpdate: PositionUpdate) {
            try {
                latestPosition = positionUpdate
                // Convert radians to degrees for latitude/longitude
                val latDegrees = positionUpdate.latitude * 180.0 / PI
                val lonDegrees = positionUpdate.longitude * 180.0 / PI
                Log.d(TAG, "Position: lat=$latDegrees, lon=$lonDegrees, acc=${positionUpdate.hPrecision}, fix=${positionUpdate.solution}")
                createAndSendTelemetry()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onPositionUpdate", e)
                onError(e)
            }
        }

        override fun onSatelliteUpdate(satelliteUpdate: SatelliteUpdate, satellitesInView: Int) {
            latestSatellites = satelliteUpdate
            latestSatellitesInView = satellitesInView
            Log.d(TAG, "Satellites: count=$satellitesInView, total=${satelliteUpdate.satellites.size}")
            // Update telemetry if we have position
            latestPosition?.let { createAndSendTelemetry() }
        }

        override fun onPowerUpdate(powerSourceState: PowerSourceState) {
            latestBattery = powerSourceState
            Log.d(TAG, "Battery: ${powerSourceState.batteryLevel}%, charging=${powerSourceState.isCharging}")
        }

        override fun onSensorStateChanged(sensorStateEvent: SensorStateEvent) {
            latestHealth = sensorStateEvent
            Log.d(TAG, "Sensor state: ${sensorStateEvent.sensorState}")
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
            onError(RuntimeException("USB connection error"))
        }

        override fun onSubscriptionHasExpired() {
            Log.e(TAG, "Subscription has expired")
            onError(RuntimeException("Subscription has expired"))
        }
    }

    fun connect(tenantId: String, deviceId: String) {
        this.tenantId = tenantId
        this.deviceId = deviceId
        
        // Run initialization on a background thread to avoid blocking
        Thread {
            try {
                Log.i(TAG, "Initializing Trimble Licensing")
                // Initialize Trimble Licensing before using SDK
                TrimbleLicensingUtil.initialize(context)
                
                Log.i(TAG, "Initializing Catalyst SDK")
                
                // Get app GUID from application ID
                val appGuid = context.packageName
                Log.d(TAG, "Using app GUID: $appGuid")
                
                // Create CatalystFacade instance
                facade = CatalystFacade(appGuid, context.applicationContext)
                
                // Load subscription (without TMM for now - can be changed to loadSubscriptionFromTrimbleMobileManager if needed)
                val loadRc = facade!!.loadSubscription()
                Log.d(TAG, "Load subscription return code: ${loadRc.code}")
                
                if (loadRc.code != DriverReturnCode.Success) {
                    val error = RuntimeException("Failed to load subscription with code: ${loadRc.code}")
                    Log.e(TAG, "Failed to load subscription", error)
                    onError(error)
                    return@Thread
                }
                
                // Initialize driver for Catalyst
                val initRc = facade!!.initDriver(DriverType.Catalyst)
                Log.d(TAG, "Init driver return code: ${initRc.code}")
                
                if (initRc.code != DriverReturnCode.Success) {
                    val error = RuntimeException("Failed to initialize driver with code: ${initRc.code}")
                    Log.e(TAG, "Failed to initialize driver", error)
                    onError(error)
                    return@Thread
                }
                
                // Add event listener
                facade!!.addCatalystEventListener(eventListener)
                
                // Connect to sensor (this may take time, especially on first connection)
                Log.i(TAG, "Attempting to connect to Catalyst sensor...")
                val connectRc = facade!!.connect()
                Log.d(TAG, "Connect return code: ${connectRc.code}")
                
                if (connectRc.code != DriverReturnCode.Success) {
                    val error = RuntimeException("Failed to connect with code: ${connectRc.code}")
                    Log.e(TAG, "Failed to connect", error)
                    onError(error)
                    return@Thread
                }
                
                isConnected = true
                Log.i(TAG, "Successfully connected to Catalyst - waiting for position updates...")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Catalyst", e)
                Log.e(TAG, "Exception stack trace", e)
                onError(e)
            }
        }.start()
    }
    
    private fun createAndSendTelemetry() {
        val position = latestPosition ?: return
        
        try {
            // Convert radians to degrees for latitude/longitude
            // Use getter methods explicitly to avoid Kotlin property access issues
            val latRadians = position.latitude
            val lonRadians = position.longitude
            val latDegrees = latRadians * 180.0 / PI
            val lonDegrees = lonRadians * 180.0 / PI
            
            // Map SolutionType to String
            val solution = position.solution
            val fixTypeName = solution?.toString() ?: "UNKNOWN"
            
            // Get precision values safely
            val hPrec = position.hPrecision
            val vPrec = position.vPrecision
            val hPrecision = if (hPrec.isNaN() || hPrec.isInfinite()) -1.0 else hPrec
            val vPrecision = if (vPrec.isNaN() || vPrec.isInfinite()) -1.0 else vPrec
            
            // Get DOP values safely
            val pdopValue = position.pdop
            val hdopValue = position.hdop
            val vdopValue = position.vdop
            
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
                latestHealth?.sensorState?.toString()?.contains("ERROR", ignoreCase = true) == true -> "ERROR"
                else -> "OK"
            }
            
            val payload = TelemetryPayload(
                tenantId = tenantId,
                deviceId = deviceId,
                latitude = latDegrees,
                longitude = lonDegrees,
                battery = latestBattery?.batteryLevel 
                    ?: DeviceInfoUtil.batteryLevel(context), // Use receiver battery if available, else phone battery
                fixType = fixTypeName,
                timestamp = Instant.now().toString(),
                health = health,
                horizontalAccuracy = hPrecision,
                verticalAccuracy = vPrecision,
                satellites = latestSatellitesInView,
                receiverBattery = latestBattery?.batteryLevel?.takeIf { it in 0..100 },
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
            
            if (isConnected && facade != null) {
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
                
                isConnected = false
            }
            
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
