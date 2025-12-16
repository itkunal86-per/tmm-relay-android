package com.example.tmmrelay.service

import com.example.tmmrelay.model.TelemetryPayload
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

object ApiClient {

    private const val API_URL = "https://your-domain.com/api/telemetry/push"
    private val client: OkHttpClient = OkHttpClient.Builder().build()

    fun send(payload: TelemetryPayload, apiKey: String? = null) {
        val json = JSONObject().apply {
            put("deviceId", payload.deviceId)
            put("latitude", payload.latitude)
            put("longitude", payload.longitude)
            put("battery", payload.battery)
            put("fixType", payload.fixType)
            put("timestamp", payload.timestamp)
            put("health", payload.health)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("tenantId", payload.tenantId)
            .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // TODO: add logging or retry policy as needed
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}
