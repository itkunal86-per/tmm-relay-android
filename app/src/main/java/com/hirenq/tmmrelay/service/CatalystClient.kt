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
            latestPosition = positionUpdate
            // Convert radians to degrees for latitude/longitude
            val latDegrees = positionUpdate.latitude * 180.0 / PI
            val lonDegrees = positionUpdate.longitude * 180.0 / PI
            Log.d(TAG, "Position: lat=$latDegrees, lon=$lonDegrees, acc=${positionUpdate.hPrecision}, fix=${positionUpdate.solution}")
            createAndSendTelemetry()
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
        
        try {
            Log.i(TAG, "Initializing Trimble Licensing")
            // Initialize Trimble Licensing before using SDK
            TrimbleLicensingUtil.initialize(context)
            
            Log.i(TAG, "Initializing Catalyst SDK")
            
            // Get app GUID from application ID
            val appGuid = context.packageName
            
            // Create CatalystFacade instance
            facade = CatalystFacade(appGuid, context.applicationContext)
            
            // Load subscription (without TMM for now - can be changed to loadSubscriptionFromTrimbleMobileManager if needed)
            val loadRc = facade!!.loadSubscription()
            Log.d(TAG, "Load subscription return code: ${loadRc.code}")
            
            if (loadRc.code != DriverReturnCode.Success) {
                val error = RuntimeException("Failed to load subscription with code: ${loadRc.code}")
                Log.e(TAG, "Failed to load subscription", error)
                onError(error)
                return
            }
            
            // Initialize driver for Catalyst
            val initRc = facade!!.initDriver(DriverType.Catalyst)
            Log.d(TAG, "Init driver return code: ${initRc.code}")
            
            if (initRc.code != DriverReturnCode.Success) {
                val error = RuntimeException("Failed to initialize driver with code: ${initRc.code}")
                Log.e(TAG, "Failed to initialize driver", error)
                onError(error)
                return
            }
            
            // Add event listener
            facade!!.addCatalystEventListener(eventListener)
            
            // Connect to sensor
            val connectRc = facade!!.connect()
            Log.d(TAG, "Connect return code: ${connectRc.code}")
            
            if (connectRc.code != DriverReturnCode.Success) {
                val error = RuntimeException("Failed to connect with code: ${connectRc.code}")
                Log.e(TAG, "Failed to connect", error)
                onError(error)
                return
            }
            
            isConnected = true
            Log.i(TAG, "Successfully connected to Catalyst")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Catalyst", e)
            onError(e)
        }
    }
    
    private fun createAndSendTelemetry() {
        val position = latestPosition ?: return
        
        try {
            // Convert radians to degrees for latitude/longitude
            val latDegrees = position.latitude * 180.0 / PI
            val lonDegrees = position.longitude * 180.0 / PI
            
            // Map SolutionType to String
            val fixTypeName = position.solution.toString()
            
            // Calculate receiver health based on position and satellite data
            val receiverHealth = when {
                fixTypeName.contains("INVALID", ignoreCase = true) || 
                fixTypeName.contains("AUTONOMOUS", ignoreCase = true) && latestSatellitesInView < 4 -> "NO_FIX"
                latestSatellitesInView < 4 -> "POOR"
                position.hPrecision > 2.5 || position.hPrecision.isNaN() -> "POOR"
                position.hPrecision < 1.0 -> "EXCELLENT"
                else -> "GOOD"
            }
            
            // Calculate overall health
            val health = when {
                latDegrees == 0.0 && lonDegrees == 0.0 -> "NO_COORDINATES"
                fixTypeName.contains("AUTONOMOUS", ignoreCase = true) && latestSatellitesInView < 4 -> "NO_FIX"
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
                horizontalAccuracy = if (position.hPrecision.isNaN()) -1.0 else position.hPrecision,
                verticalAccuracy = if (position.vPrecision.isNaN()) -1.0 else position.vPrecision,
                satellites = latestSatellitesInView,
                receiverBattery = latestBattery?.batteryLevel?.takeIf { it in 0..100 },
                pdop = if (position.pdop.isNaN()) null else position.pdop,
                hdop = if (position.hdop.isNaN()) null else position.hdop,
                vdop = if (position.vdop.isNaN()) null else position.vdop,
                receiverHealth = receiverHealth
            )
            
            onMessage(payload)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating telemetry payload", e)
            onError(e)
        }
    }

    fun close() {
        try {
            Log.i(TAG, "Closing Catalyst client")
            
            if (isConnected) {
                facade?.endSurvey()
                facade?.disconnectFromSensor()
                isConnected = false
            }
            
            facade?.removeCatalystEventListener(eventListener)
            facade?.releaseDriver()
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
