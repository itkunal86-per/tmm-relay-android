package com.hirenq.tmmrelay.service

import android.util.Log
import com.hirenq.tmmrelay.model.TelemetryPayload
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ApiClient {

    private const val TAG = "ApiClient"
    private const val API_URL = "https://altgeo-api.hirenq.com/api/Device/pushdata"
    private val client: OkHttpClient = OkHttpClient.Builder()
        .build()

    fun send(
        payload: TelemetryPayload, 
        apiKey: String? = null,
        onPostSent: ((String, String) -> Unit)? = null
    ) {
        // Ensure Timestamp is in UTC format (with Z suffix) - parse and reformat if needed
        val timestamp = try {
            // If payload.timestamp is already in ISO format, use it; otherwise parse and format
            Instant.parse(payload.timestamp).toString()
        } catch (e: Exception) {
            // Fallback to current time in UTC if parsing fails
            Instant.now().toString()
        }

        // Generate CurrentTimestamp in IST (UTC+5:30) format like "2025-12-17T12:45:30+05:30"
        val currentTimestamp = Instant.now()
            .atZone(ZoneId.of("Asia/Kolkata"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

        // Match the exact JSON structure from the working curl command
        val json = JSONObject().apply {
            put("DeviceId", payload.deviceId)
            put("Latitude", payload.latitude)
            put("Longitude", payload.longitude)
            put("Battery", payload.battery)
            put("FixType", payload.fixType)
            put("Timestamp", timestamp)
            put("CurrentTimestamp", currentTimestamp)
            // Add user details if available
            payload.userId?.let { put("UserId", it) }
            payload.userName?.let { put("UserName", it) }
            payload.userEmail?.let { put("UserEmail", it) }
        }

        val jsonString = json.toString()
        Log.d(TAG, "Sending POST request to $API_URL")
        Log.d(TAG, "Request body: $jsonString")

        val body = jsonString.toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url(API_URL)
            .addHeader("Content-Type", "application/json")
            .post(body)

        // Add Authorization header if apiKey is provided
        apiKey?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
        }

        val request = requestBuilder.build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "API request failed", e)
                e.printStackTrace()
                // Notify callback even on failure
                onPostSent?.invoke(
                    Instant.now().atZone(ZoneId.of("Asia/Kolkata"))
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    "Failed: ${e.message}"
                )
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d(TAG, "API response code: ${response.code}")
                Log.d(TAG, "API response body: $responseBody")
                
                val timestamp = Instant.now()
                    .atZone(ZoneId.of("Asia/Kolkata"))
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "API request failed with code ${response.code}: $responseBody")
                    onPostSent?.invoke(timestamp, "Error ${response.code}: $responseBody")
                } else {
                    Log.i(TAG, "API request successful")
                    // Notify callback with timestamp and payload summary
                    val payloadSummary = "Lat:${payload.latitude}, Lng:${payload.longitude}, Bat:${payload.battery}%"
                    onPostSent?.invoke(timestamp, payloadSummary)
                }
                
                response.close()
            }
        })
    }
}



