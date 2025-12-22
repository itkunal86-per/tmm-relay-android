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
                    Log.e("TMM_RAW", "RAW => $text")
                    val json = JSONObject(text)

                    if (!json.has("latitude") || !json.has("longitude")) return

                    val latitude = json.optDouble("latitude", 0.0)
                    val longitude = json.optDouble("longitude", 0.0)
                    val fixType = json.optString("fixType", "NO_FIX")

                    val horizontalAccuracy = json.optDouble("horizontalAccuracy", -1.0)
                    val verticalAccuracy = json.optDouble("verticalAccuracy", -1.0)
                    val satellites = json.optInt("satellites", 0)

                    // ---- Receiver Battery (optional) ----
                    val receiverBattery =
                        json.optInt("receiverBattery",
                        json.optInt("batteryLevel",
                        json.optInt("battery", -1)))
                            .takeIf { it in 0..100 }

                    // ---- Precision DOPs (optional) ----
                    val pdop = json.optDouble("pdop", -1.0).takeIf { it > 0 }
                    val hdop = json.optDouble("hdop", -1.0).takeIf { it > 0 }
                    val vdop = json.optDouble("vdop", -1.0).takeIf { it > 0 }

                    // ---- Receiver Health (derived) ----
                    val receiverHealth = when {
                        fixType == "NO_FIX" -> "NO_FIX"
                        satellites < 4 -> "POOR"
                        hdop != null && hdop > 2.5 -> "POOR"
                        fixType.contains("FIX", true) && hdop != null && hdop < 1.0 -> "EXCELLENT"
                        else -> "GOOD"
                    }

                    val payload = TelemetryPayload(
                        tenantId = tenantId,
                        deviceId = deviceId,
                        latitude = latitude,
                        longitude = longitude,
                        battery = DeviceInfoUtil.batteryLevel(context), // phone
                        receiverBattery = receiverBattery,
                        fixType = fixType,
                        horizontalAccuracy = horizontalAccuracy,
                        verticalAccuracy = verticalAccuracy,
                        pdop = pdop,
                        hdop = hdop,
                        vdop = vdop,
                        satellites = satellites,
                        receiverHealth = receiverHealth,
                        timestamp = Instant.now().toString()
                    )

                    onMessage(payload)

                } catch (e: Exception) {
                    Log.e(TAG, "GNSS parse error", e)
                    onError(e)
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
