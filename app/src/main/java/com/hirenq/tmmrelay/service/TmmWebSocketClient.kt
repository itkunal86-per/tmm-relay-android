package com.hirenq.tmmrelay.service

import android.content.Context
import android.util.Log
import com.hirenq.tmmrelay.model.TelemetryPayload
import com.hirenq.tmmrelay.util.DeviceInfoUtil
import okhttp3.*
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Trimble Mobile Manager (TMM) WebSocket Client
 *
 * Connects to local TMM WebSocket server (ws://127.0.0.1:9635)
 * Receives GNSS coordinates from DA2 / Catalyst receivers.
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

    private val tmmUrl = "ws://127.0.0.1:9635"
    private var webSocket: WebSocket? = null

    fun connect(tenantId: String, deviceId: String) {
        Log.d(TAG, "Connecting to TMM WebSocket: $tmmUrl")

        val request = Request.Builder()
            .url(tmmUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Connected to Trimble Mobile Manager WebSocket")

                // NOTE:
                // Some TMM versions auto-push location.
                // Some ignore subscription completely.
                // Keeping this OPTIONAL and non-blocking.
                try {
                    val subscribe = JSONObject().apply {
                        put("type", "subscribe")
                        put("topic", "location")
                    }
                    ws.send(subscribe.toString())
                    Log.d(TAG, "Sent optional subscribe message")
                } catch (e: Exception) {
                    Log.w(TAG, "Subscribe message failed (safe to ignore)", e)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    // 🔴 CRITICAL: Always log raw message
                    Log.e("TMM_RAW", "RAW => $text")

                    val json = JSONObject(text)

                    // ✅ DO NOT depend on "type"
                    val hasLat = json.has("latitude")
                    val hasLng = json.has("longitude")

                    if (!hasLat || !hasLng) {
                        Log.d(TAG, "No latitude/longitude found in message, ignoring")
                        return
                    }

                    val latitude = json.optDouble("latitude", 0.0)
                    val longitude = json.optDouble("longitude", 0.0)
                    val height = json.optDouble("height", 0.0)
                    val fixType = json.optString("fixType", "NO_FIX")
                    val horizontalAccuracy = json.optDouble("horizontalAccuracy", -1.0)
                    val verticalAccuracy = json.optDouble("verticalAccuracy", -1.0)
                    val satellites = json.optInt("satellites", -1)
                    val timestampStr = json.optString("timestamp", "")

                    Log.d(
                        TAG,
                        "Parsed GNSS => Lat:$latitude Lng:$longitude Fix:$fixType Acc:$horizontalAccuracy Sat:$satellites"
                    )

                    // Battery is device-based (TMM does not provide it reliably)
                    val battery = DeviceInfoUtil.batteryLevel(context)

                    val payloadTimestamp = try {
                        if (timestampStr.isNotEmpty()) {
                            Instant.parse(timestampStr).toString()
                        } else {
                            Instant.now().toString()
                        }
                    } catch (e: Exception) {
                        Instant.now().toString()
                    }

                    // Optional user fields (may or may not exist)
                    val userId =
                        json.optString("userId")
                            .ifEmpty { json.optString("username") }
                            .ifEmpty { null }

                    val userName =
                        json.optString("userName")
                            .ifEmpty { json.optString("name") }
                            .ifEmpty { null }

                    val userEmail =
                        json.optString("userEmail")
                            .ifEmpty { json.optString("email") }
                            .ifEmpty { null }

                    val payload = TelemetryPayload(
                        tenantId = tenantId,
                        deviceId = deviceId,
                        latitude = latitude,
                        longitude = longitude,
                        battery = battery,
                        fixType = fixType,
                        timestamp = payloadTimestamp,
                        horizontalAccuracy = horizontalAccuracy,
                        verticalAccuracy = verticalAccuracy,
                        satellites = satellites,
                        health = DeviceInfoUtil.health(battery, fixType, null),
                        userId = userId,
                        userName = userName,
                        userEmail = userEmail
                    )

                    onMessage(payload)

                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to parse TMM message", ex)
                    onError(ex)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                response?.let {
                    Log.e(TAG, "HTTP ${it.code} - ${it.message}")
                }
                Log.e(TAG, "Check: TMM running, Bluetooth connected, GNSS fix available")
                onError(t)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
            }
        })
    }

    fun close() {
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        Log.d(TAG, "WebSocket connection closed")
    }
}
