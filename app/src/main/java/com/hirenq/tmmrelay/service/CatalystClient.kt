package com.hirenq.tmmrelay.service

import android.content.Context
import android.util.Log
import com.hirenq.tmmrelay.model.TelemetryPayload
import com.hirenq.tmmrelay.util.DeviceInfoUtil
import trimble.jssi.android.catalystfacade.CatalystFacade
import trimble.jssi.android.catalystfacade.ICatalystEventListener
import trimble.jssi.android.catalystfacade.PositionUpdate
import trimble.jssi.android.catalystfacade.SatelliteUpdate
import trimble.jssi.android.catalystfacade.PowerSourceState
import trimble.jssi.android.catalystfacade.SensorStateEvent
import trimble.jssi.android.catalystfacade.ReturnCode
import java.time.Instant

class CatalystClient(
    private val context: Context,
    private val onMessage: (TelemetryPayload) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {

    private val TAG = "CatalystClient"
    private var facade: CatalystFacade? = null
    private var tenantId: String = ""
    private var deviceId: String = ""
    private var isSurveying = false
    
    // Track latest values from different event types
    private var latestPosition: PositionUpdate? = null
    private var latestSatellites: SatelliteUpdate? = null
    private var latestBattery: PowerSourceState? = null
    private var latestHealth: SensorStateEvent? = null

    fun connect(tenantId: String, deviceId: String) {
        this.tenantId = tenantId
        this.deviceId = deviceId
        
        try {
            Log.i(TAG, "Initializing Catalyst SDK")
            
            // Create CatalystFacade instance
            facade = CatalystFacade(context.applicationContext)
            
            // Initialize the facade
            val initRc = facade!!.initialize()
            Log.d(TAG, "Catalyst init return code: $initRc")
            
            if (initRc != ReturnCode.SUCCESS) {
                val error = RuntimeException("Catalyst initialization failed with code: $initRc")
                Log.e(TAG, "Failed to initialize Catalyst", error)
                onError(error)
                return
            }
            
            // Set up event listener
            facade!!.setEventListener { event ->
                try {
                    when (event) {
                        is PositionUpdate -> {
                            latestPosition = event
                            Log.d(TAG, "Position: lat=${event.latitude}, lon=${event.longitude}, acc=${event.horizontalAccuracy}, fix=${event.fixType}")
                            // Create telemetry payload when we have position data
                            createAndSendTelemetry()
                        }
                        is SatelliteUpdate -> {
                            latestSatellites = event
                            Log.d(TAG, "Satellites: count=${event.satelliteCount}")
                            // Update telemetry if we have position
                            latestPosition?.let { createAndSendTelemetry() }
                        }
                        is PowerSourceState -> {
                            latestBattery = event
                            Log.d(TAG, "Battery: ${event.batteryPercentage}%")
                        }
                        is SensorStateEvent -> {
                            latestHealth = event
                            Log.d(TAG, "Sensor health: ${event.healthState}")
                        }
                        else -> {
                            Log.d(TAG, "Other event: ${event.javaClass.simpleName}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing Catalyst event", e)
                    onError(e)
                }
            }
            
            // Start survey
            startSurvey()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Catalyst", e)
            onError(e)
        }
    }
    
    private fun startSurvey() {
        if (isSurveying) {
            Log.w(TAG, "Survey already started")
            return
        }
        
        try {
            Log.i(TAG, "Starting Catalyst survey")
            facade?.startSurvey()
            isSurveying = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start survey", e)
            onError(e)
        }
    }
    
    private fun createAndSendTelemetry() {
        val position = latestPosition ?: return
        
        try {
            // Map PositionUpdate and other events to TelemetryPayload
            // fixType might be String or enum - convert to String
            val fixTypeName = position.fixType?.toString() ?: "UNKNOWN"
            
            // Calculate receiver health based on position and satellite data
            val receiverHealth = when {
                fixTypeName.contains("INVALID", ignoreCase = true) || 
                fixTypeName.contains("NO_FIX", ignoreCase = true) -> "NO_FIX"
                (latestSatellites?.satelliteCount ?: 0) < 4 -> "POOR"
                (position.horizontalAccuracy ?: Double.MAX_VALUE) > 2.5 -> "POOR"
                (position.horizontalAccuracy ?: Double.MAX_VALUE) < 1.0 -> "EXCELLENT"
                else -> "GOOD"
            }
            
            // Calculate overall health
            val health = when {
                position.latitude == 0.0 && position.longitude == 0.0 -> "NO_COORDINATES"
                fixTypeName.contains("INVALID", ignoreCase = true) || 
                fixTypeName.contains("NO_FIX", ignoreCase = true) -> "NO_FIX"
                latestHealth?.healthState?.contains("ERROR", ignoreCase = true) == true -> "ERROR"
                else -> "OK"
            }
            
            val payload = TelemetryPayload(
                tenantId = tenantId,
                deviceId = deviceId,
                latitude = position.latitude,
                longitude = position.longitude,
                battery = latestBattery?.batteryPercentage?.toInt() 
                    ?: DeviceInfoUtil.batteryLevel(context), // Use receiver battery if available, else phone battery
                fixType = fixTypeName,
                timestamp = Instant.now().toString(),
                health = health,
                horizontalAccuracy = position.horizontalAccuracy ?: -1.0,
                verticalAccuracy = position.verticalAccuracy ?: -1.0,
                satellites = latestSatellites?.satelliteCount ?: 0,
                receiverBattery = latestBattery?.batteryPercentage?.toInt()?.takeIf { it in 0..100 },
                pdop = position.pdop,
                hdop = position.hdop,
                vdop = position.vdop,
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
            
            if (isSurveying) {
                facade?.stopSurvey()
                isSurveying = false
            }
            
            facade?.shutdown()
            facade = null
            
            // Clear cached data
            latestPosition = null
            latestSatellites = null
            latestBattery = null
            latestHealth = null

        } catch (e: Exception) {
            Log.e(TAG, "Error closing Catalyst client", e)
        }
    }
}
