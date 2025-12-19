package com.hirenq.tmmrelay.service

import android.content.Context
import android.util.Log
import com.hirenq.tmmrelay.model.TelemetryPayload
import com.hirenq.tmmrelay.util.DeviceInfoUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Trimble Mobile Manager (TMM) WebSocket Client
 * 
 * Connects to TMM's local WebSocket server at ws://127.0.0.1:9635
 * to receive live GNSS receiver coordinates from DA2/Catalyst receivers.
 * 
 * This is the ONLY way to get live GNSS receiver coordinates from a DA2 on Android
 * without Trimble Cloud / TPPAS.
 * 
 * Requirements:
 * - Trimble Mobile Manager must be running
 * - Receiver (DA2/Catalyst) must be connected via Bluetooth
 * - No internet required for WebSocket connection
 */
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

    // TMM local WebSocket endpoint (undocumented but widely used)
    private val tmmUrl = "ws://127.0.0.1:9635"
    private var webSocket: WebSocket? = null

    fun connect(tenantId: String, deviceId: String) {
        Log.d(TAG, "Connecting to TMM WebSocket: $tmmUrl")
        val request = Request.Builder()
            .url(tmmUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to TMM WebSocket")
                
                // Subscribe to location updates (some TMM versions require this)
                try {
                    val subscribeMessage = JSONObject().apply {
                        put("type", "subscribe")
                        put("topic", "location")
                    }
                    webSocket.send(subscribeMessage.toString())
                    Log.d(TAG, "Sent subscription request for location topic")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send subscription message (may auto-push)", e)
                }
                
                super.onOpen(webSocket, response)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    Log.d(TAG, "TMM Message: $text")
                    val json = JSONObject(text)

                    // Check if this is a location message
                    val messageType = json.optString("type", "")
                    if (messageType != "location" && messageType.isNotEmpty()) {
                        Log.d(TAG, "Ignoring non-location message type: $messageType")
                        return
                    }

                    // Parse TMM location message format
                    // Sample: { "type": "location", "latitude": 26.14331245, "longitude": 91.78923112, 
                    //          "height": 54.23, "fixType": "RTK_FIXED", "horizontalAccuracy": 0.012, ... }
                    val latitude = json.optDouble("latitude", 0.0)
                    val longitude = json.optDouble("longitude", 0.0)
                    val height = json.optDouble("height", 0.0)
                    val fixType = json.optString("fixType", "UNKNOWN")
                    val horizontalAccuracy = json.optDouble("horizontalAccuracy", -1.0)
                    val verticalAccuracy = json.optDouble("verticalAccuracy", -1.0)
                    val satellites = json.optInt("satellites", -1)
                    val timestamp = json.optString("timestamp", "")

                    // Validate coordinates (0,0 is invalid)
                    if (latitude == 0.0 && longitude == 0.0) {
                        Log.w(TAG, "Received invalid coordinates (0,0), skipping")
                        return
                    }

                    Log.d(TAG, "Parsed location - Lat: $latitude, Lng: $longitude, " +
                            "Height: $height, FixType: $fixType, Accuracy: $horizontalAccuracy, " +
                            "Satellites: $satellites")

                    // Get battery level from device (TMM may not provide this)
                    val battery = DeviceInfoUtil.batteryLevel(context)

                    // Use timestamp from TMM if available, otherwise use current time
                    val payloadTimestamp = if (timestamp.isNotEmpty()) {
                        try {
                            Instant.parse(timestamp).toString()
                        } catch (e: Exception) {
                            Instant.now().toString()
                        }
                    } else {
                        Instant.now().toString()
                    }

                    val payload = TelemetryPayload(
                        tenantId = tenantId,
                        deviceId = deviceId,
                        latitude = latitude,
                        longitude = longitude,
                        battery = battery,
                        fixType = fixType,
                        timestamp = payloadTimestamp,
                        health = DeviceInfoUtil.health(battery, fixType, null)
                    )

                    onMessage(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "Error parsing WebSocket message", ex)
                    onError(ex)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                if (response != null) {
                    Log.e(TAG, "Response: ${response.code} - ${response.message}")
                }
                Log.e(TAG, "Ensure Trimble Mobile Manager is running and receiver is connected")
                onError(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: code=$code, reason=$reason")
                super.onClosing(webSocket, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: code=$code, reason=$reason")
                super.onClosed(webSocket, code, reason)
            }
        })
    }

    fun close() {
        webSocket?.close(1000, "closing")
        webSocket = null
        Log.d(TAG, "WebSocket connection closed")
    }
}
