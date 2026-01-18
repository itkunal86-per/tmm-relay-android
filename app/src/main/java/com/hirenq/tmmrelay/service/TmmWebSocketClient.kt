package com.hirenq.tmmrelay.service

import android.content.Context
import android.util.Log
import com.hirenq.tmmrelay.model.TelemetryPayload
import com.hirenq.tmmrelay.util.DeviceInfoUtil
import okhttp3.*
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class TmmWebSocketClient(
    private val context: Context,
    private val onMessage: (TelemetryPayload) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {

    private val TAG = "TmmWebSocketClient"

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val tmmUrl = "ws://127.0.0.1:9635"
    private var webSocket: WebSocket? = null

    fun connect(tenantId: String, deviceId: String) {

        val request = Request.Builder()
            .url(tmmUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Connected to TMM WebSocket")

                // Optional subscribe (safe)
                try {
                    ws.send(
                        JSONObject()
                            .put("type", "subscribe")
                            .put("topic", "location")
                            .toString()
                    )
                } catch (_: Exception) {}
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    Log.d("TMM_RAW", text)
                    val json = JSONObject(text)

                    val latitude = json.optDouble("latitude", 0.0)
                    val longitude = json.optDouble("longitude", 0.0)
                    val fixType = json.optString("fixType", "NO_FIX")

                    val horizontalAccuracy = json.optDouble("horizontalAccuracy", -1.0)
                    val verticalAccuracy = json.optDouble("verticalAccuracy", -1.0)
                    val satellites = json.optInt("satellites", 0)

                    val receiverBattery =
                        json.optInt("receiverBattery",
                        json.optInt("battery", -1))
                            .takeIf { it in 0..100 }

                    val pdop = json.optDouble("pdop", -1.0).takeIf { it > 0 }
                    val hdop = json.optDouble("hdop", -1.0).takeIf { it > 0 }
                    val vdop = json.optDouble("vdop", -1.0).takeIf { it > 0 }

                    val receiverHealth = when {
                        fixType == "NO_FIX" -> "NO_FIX"
                        satellites < 4 -> "POOR"
                        hdop != null && hdop > 2.5 -> "POOR"
                        fixType.contains("FIX", true) && hdop != null && hdop < 1.0 -> "EXCELLENT"
                        else -> "GOOD"
                    }

                    val health = when {
                        latitude == 0.0 && longitude == 0.0 -> "NO_COORDINATES"
                        fixType == "NO_FIX" -> "NO_FIX"
                        else -> "OK"
                    }

                    // Convert coordinates: use non-zero values or null
                    val da2Latitude = if (latitude != 0.0) latitude else null
                    val da2Longitude = if (longitude != 0.0) longitude else null
                    val da2HorizontalAccuracy = if (horizontalAccuracy >= 0) horizontalAccuracy else null
                    val da2VerticalAccuracy = if (verticalAccuracy >= 0) verticalAccuracy else null
                    val da2Satellites = if (satellites >= 0) satellites else null
                    
                    val payload = TelemetryPayload(
                        tenantId = tenantId,
                        // DA2 receiver data (nullable) - fields 5-17 (from WebSocket)
                        deviceId = null, // DA2 receiver device ID (not available from WebSocket)
                        latitude = da2Latitude, // DA2 coordinates from WebSocket
                        longitude = da2Longitude, // DA2 coordinates from WebSocket
                        battery = receiverBattery, // DA2 receiver battery
                        fixType = fixType, // DA2 fix type
                        timestamp = Instant.now().toString(), // DA2 timestamp
                        health = health, // DA2 health
                        horizontalAccuracy = da2HorizontalAccuracy, // DA2 horizontal accuracy
                        verticalAccuracy = da2VerticalAccuracy, // DA2 vertical accuracy
                        satellites = da2Satellites, // DA2 satellites
                        userId = null, // DA2 user data
                        userName = null, // DA2 user data
                        userEmail = null, // DA2 user data
                        // Mobile GPS data (always included)
                        mobileDeviceId = deviceId, // Mobile device ID (always present)
                        mobileLatitude = null, // Mobile GPS coordinates (not available from WebSocket)
                        mobileLongitude = null, // Mobile GPS coordinates (not available from WebSocket)
                        mobileAccuracy = null, // Mobile GPS accuracy (not available)
                        mobileBattery = DeviceInfoUtil.batteryLevel(context), // Mobile device battery
                        dataSource = "TRIMBLE" // Data from WebSocket (DA2)
                    )

                    onMessage(payload)

                } catch (e: Exception) {
                    Log.e(TAG, "GNSS parse error", e)
                    onError(e)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                onError(t)
            }
        })
    }

    fun close() {
        webSocket?.close(1000, "Service stopped")
        webSocket = null
    }
}
