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

    private val tmmUrl = "ws://127.0.0.1:4949/positionStream"
    private var webSocket: WebSocket? = null

    fun connect(tenantId: String, deviceId: String) {
        Log.d(TAG, "Connecting to WebSocket: $tmmUrl")
        val request = Request.Builder().url(tmmUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connection opened")
                super.onOpen(webSocket, response)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    Log.d(TAG, "Received WebSocket message: $text")
                    val json = JSONObject(text)

                    // Use optDouble to handle missing fields gracefully
                    val latitude = json.optDouble("latitude", 0.0)
                    val longitude = json.optDouble("longitude", 0.0)
                    val battery = json.optInt("battery", DeviceInfoUtil.batteryLevel(context))
                    val fixType = json.optString("fixType", "UNKNOWN")
                    
                    Log.d(TAG, "Parsed: Lat=$latitude, Lng=$longitude, Battery=$battery, FixType=$fixType")

                    val payload = TelemetryPayload(
                        tenantId = tenantId,
                        deviceId = json.optString("deviceId", deviceId),
                        latitude = latitude,
                        longitude = longitude,
                        battery = battery,
                        fixType = fixType,
                        timestamp = Instant.now().toString(),
                        health = DeviceInfoUtil.health(battery, fixType, null)
                    )

                    onMessage(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "Error parsing WebSocket message", ex)
                    onError(ex)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket connection failed", t)
                Log.e(TAG, "Response: ${response?.code} - ${response?.message}")
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
    }
}
