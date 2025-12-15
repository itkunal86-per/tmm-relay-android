package com.example.tmmrelay.service

import android.content.Context
import com.example.tmmrelay.model.TelemetryPayload
import com.example.tmmrelay.util.DeviceInfoUtil
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

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val tmmUrl = "ws://127.0.0.1:4949/positionStream"
    private var webSocket: WebSocket? = null

    fun connect(tenantId: String, deviceId: String) {
        val request = Request.Builder().url(tmmUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)

                    val battery = json.optInt("battery", DeviceInfoUtil.batteryLevel(context))
                    val fixType = json.optString("fixType", "UNKNOWN")
                    val payload = TelemetryPayload(
                        tenantId = tenantId,
                        deviceId = json.optString("deviceId", deviceId),
                        latitude = json.getDouble("latitude"),
                        longitude = json.getDouble("longitude"),
                        battery = battery,
                        fixType = fixType,
                        timestamp = Instant.now().toString(),
                        health = DeviceInfoUtil.health(battery, fixType, null)
                    )

                    onMessage(payload)
                } catch (ex: Exception) {
                    onError(ex)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError(t)
            }
        })
    }

    fun close() {
        webSocket?.close(1000, "closing")
        webSocket = null
    }
}
