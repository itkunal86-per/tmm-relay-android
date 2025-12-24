package com.hirenq.tmmrelay.service

import android.content.Context
import android.util.Log
import com.hirenq.tmmrelay.model.TelemetryPayload
import com.hirenq.tmmrelay.util.DeviceInfoUtil
import com.trimble.catalyst.facade.CatalystFacade
import com.trimble.catalyst.facade.licensing.*
import com.trimble.catalyst.facade.receiver.*
import com.trimble.catalyst.facade.positioning.*
import java.time.Instant

class CatalystClient(
    private val context: Context,
    private val onMessage: (TelemetryPayload) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {

    private val TAG = "CatalystClient"
    private var isInitialized = false
    private var isPositioning = false
    private var isLicenseActive = false
    private var tenantId: String = ""
    private var deviceId: String = ""
    
    private var licenseListener: LicenseListener? = null
    private var receiverListener: ReceiverListener? = null
    private var positionListener: PositionListener? = null

    fun connect(tenantId: String, deviceId: String) {
        this.tenantId = tenantId
        this.deviceId = deviceId
        
        try {
            Log.i(TAG, "Initializing Catalyst SDK")
            
            if (!isInitialized) {
                CatalystFacade.initialize(context.applicationContext)
                isInitialized = true
            }

            // Set up license listener FIRST (required before connecting)
            licenseListener = object : LicenseListener {
                override fun onLicenseStateChanged(state: LicenseState) {
                    Log.d(TAG, "License state: $state")
                    
                    when (state) {
                        LicenseState.ACTIVE -> {
                            isLicenseActive = true
                            connectReceiver()
                        }
                        LicenseState.INACTIVE -> {
                            isLicenseActive = false
                            Log.w(TAG, "License inactive - subscription may be expired")
                            onError(RuntimeException("License inactive"))
                        }
                        LicenseState.CHECKING -> {
                            Log.d(TAG, "Checking license status...")
                        }
                        LicenseState.ERROR -> {
                            isLicenseActive = false
                            Log.e(TAG, "License error state")
                            onError(RuntimeException("License error"))
                        }
                    }
                }

                override fun onLicenseError(error: LicenseError) {
                    Log.e(TAG, "License error: $error")
                    onError(RuntimeException("License error: $error"))
                }
            }

            CatalystFacade.addLicenseListener(licenseListener!!)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Catalyst", e)
            onError(e)
        }
    }

    private fun connectReceiver() {
        if (!isLicenseActive) {
            Log.w(TAG, "Cannot connect receiver - license not active")
            return
        }

        try {
            Log.i(TAG, "Connecting DA2 receiver")

            receiverListener = object : ReceiverListener {
                override fun onReceiverConnected(receiver: ReceiverInfo) {
                    Log.i(TAG, "DA2 Receiver Connected: ${receiver.model}")
                    startPositioning()
                }

                override fun onReceiverDisconnected() {
                    Log.w(TAG, "DA2 Receiver Disconnected")
                    isPositioning = false
                }

                override fun onReceiverError(error: ReceiverError) {
                    Log.e(TAG, "Receiver error: $error")
                    onError(RuntimeException("Receiver error: $error"))
                }
            }

            CatalystFacade.addReceiverListener(receiverListener!!)
            CatalystFacade.connectReceiver()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect receiver", e)
            onError(e)
        }
    }

    private fun startPositioning() {
        if (isPositioning) {
            Log.w(TAG, "Positioning already started")
            return
        }

        if (!isLicenseActive) {
            Log.w(TAG, "Cannot start positioning - license not active")
            return
        }

        try {
            Log.i(TAG, "Starting positioning")

            val options = PositioningOptions.Builder()
                .setAccuracy(PositionAccuracy.HIGH)
                .build()

            positionListener = object : PositionListener {
                override fun onPositionUpdate(position: Position) {
                    try {
                        val payload = mapToTelemetryPayload(position)
                        Log.d(TAG, "Position update: lat=${position.latitude}, lon=${position.longitude}, fix=${position.fixType.name}")
                        onMessage(payload)

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing position update", e)
                        onError(e)
                    }
                }

                override fun onPositionError(error: PositionError) {
                    Log.e(TAG, "Position error: $error")
                    onError(RuntimeException("Position error: $error"))
                }
            }

            CatalystFacade.startPositioning(options, positionListener!!)
            isPositioning = true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start positioning", e)
            onError(e)
        }
    }

    private fun mapToTelemetryPayload(position: Position): TelemetryPayload {
        val fixTypeName = position.fixType.name
        
        // Get receiver info if available
        val receiverInfo = try {
            CatalystFacade.getReceiverInfo()
        } catch (e: Exception) {
            null
        }
        
        val actualDeviceId = receiverInfo?.serialNumber ?: deviceId
        
        // Calculate receiver health
        val receiverHealth = when {
            position.fixType == FixType.INVALID -> "NO_FIX"
            (position.satellitesUsed ?: 0) < 4 -> "POOR"
            (position.horizontalAccuracy ?: 0.0) > 2.5 -> "POOR"
            position.fixType != FixType.INVALID && (position.horizontalAccuracy ?: 0.0) < 1.0 -> "EXCELLENT"
            else -> "GOOD"
        }

        // Calculate overall health
        val health = when {
            position.latitude == 0.0 && position.longitude == 0.0 -> "NO_COORDINATES"
            position.fixType == FixType.INVALID -> "NO_FIX"
            else -> "OK"
        }

        return TelemetryPayload(
            tenantId = tenantId,
            deviceId = actualDeviceId,
            latitude = position.latitude,
            longitude = position.longitude,
            battery = DeviceInfoUtil.batteryLevel(context), // phone battery
            fixType = fixTypeName,
            timestamp = Instant.now().toString(),
            health = health,
            horizontalAccuracy = position.horizontalAccuracy ?: -1.0,
            verticalAccuracy = position.verticalAccuracy ?: -1.0,
            satellites = position.satellitesUsed ?: 0,
            receiverBattery = position.receiverBattery?.takeIf { it in 0..100 },
            pdop = position.pdop,
            hdop = position.hdop,
            vdop = position.vdop,
            receiverHealth = receiverHealth
        )
    }

    fun close() {
        try {
            Log.i(TAG, "Closing Catalyst client")
            
            if (isPositioning) {
                CatalystFacade.stopPositioning()
                isPositioning = false
            }

            CatalystFacade.disconnectReceiver()
            
            // Clean up listeners
            licenseListener?.let {
                try {
                    CatalystFacade.removeLicenseListener(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not remove license listener", e)
                }
            }
            
            receiverListener?.let {
                try {
                    CatalystFacade.removeReceiverListener(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not remove receiver listener", e)
                }
            }
            
            licenseListener = null
            receiverListener = null
            positionListener = null
            isLicenseActive = false

        } catch (e: Exception) {
            Log.e(TAG, "Error closing Catalyst client", e)
        }
    }
}

