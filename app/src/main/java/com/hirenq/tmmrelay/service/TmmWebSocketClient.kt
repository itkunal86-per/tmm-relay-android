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

                    val payload = TelemetryPayload(
                        tenantId = tenantId,
                        deviceId = deviceId,
                        latitude = latitude,
                        longitude = longitude,
                        battery = DeviceInfoUtil.batteryLevel(context), // phone
                        fixType = fixType,
                        timestamp = Instant.now().toString(),
                        health = health,
                        horizontalAccuracy = horizontalAccuracy,
                        verticalAccuracy = verticalAccuracy,
                        satellites = satellites,
                        receiverBattery = receiverBattery,
                        pdop = pdop,
                        hdop = hdop,
                        vdop = vdop,
                        receiverHealth = receiverHealth
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
