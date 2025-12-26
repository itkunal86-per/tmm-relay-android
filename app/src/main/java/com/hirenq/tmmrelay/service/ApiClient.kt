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
        onPostSent: ((String, String, Boolean) -> Unit)? = null
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

        // Include all fields from TelemetryPayload in the POST request
        val json = JSONObject().apply {
            // Required fields
            put("TenantId", payload.tenantId)
            put("DeviceId", payload.deviceId)
            put("Latitude", payload.latitude)
            put("Longitude", payload.longitude)
            put("Battery", payload.battery)
            put("FixType", payload.fixType)
            put("Timestamp", timestamp)
            put("CurrentTimestamp", currentTimestamp)
            put("Health", payload.health)
            put("HorizontalAccuracy", payload.horizontalAccuracy)
            put("VerticalAccuracy", payload.verticalAccuracy)
            put("Satellites", payload.satellites)
            
            // Optional user details
            payload.userId?.let { put("UserId", it) }
            payload.userName?.let { put("UserName", it) }
            payload.userEmail?.let { put("UserEmail", it) }
            
            // Optional receiver details
            payload.receiverBattery?.let { put("ReceiverBattery", it) }
            payload.receiverHealth?.let { put("ReceiverHealth", it) }
            
            // Optional DOP values
            payload.pdop?.let { put("PDOP", it) }
            payload.hdop?.let { put("HDOP", it) }
            payload.vdop?.let { put("VDOP", it) }
        }

        val jsonString = json.toString()
        Log.i(TAG, "=== Sending POST request to $API_URL ===")
        Log.i(TAG, "Full payload JSON: $jsonString")
        Log.d(TAG, "Payload fields: TenantId=${payload.tenantId}, DeviceId=${payload.deviceId}, " +
                "Lat=${payload.latitude}, Lng=${payload.longitude}, Battery=${payload.battery}, " +
                "FixType=${payload.fixType}, Health=${payload.health}, " +
                "HAcc=${payload.horizontalAccuracy}, VAcc=${payload.verticalAccuracy}, " +
                "Satellites=${payload.satellites}, ReceiverBattery=${payload.receiverBattery}, " +
                "ReceiverHealth=${payload.receiverHealth}, PDOP=${payload.pdop}, " +
                "HDOP=${payload.hdop}, VDOP=${payload.vdop}")

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
                // Notify callback even on failure (isSuccess = false)
                val timestamp = Instant.now().atZone(ZoneId.of("Asia/Kolkata"))
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                val errorMsg = "Failed: ${e.message}"
                Log.d(TAG, "Invoking onPostSent callback on failure: $timestamp - $errorMsg")
                onPostSent?.invoke(timestamp, errorMsg, false)
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
                    val errorMsg = "Error ${response.code}: $responseBody"
                    Log.d(TAG, "Invoking onPostSent callback on error: $timestamp - $errorMsg")
                    onPostSent?.invoke(timestamp, errorMsg, false)
                } else {
                    Log.i(TAG, "API request successful")
                    // Notify callback with timestamp and payload summary (isSuccess = true)
                    val payloadSummary = "Lat:${payload.latitude}, Lng:${payload.longitude}, Bat:${payload.battery}%"
                    Log.d(TAG, "Invoking onPostSent callback on success: $timestamp - $payloadSummary")
                    onPostSent?.invoke(timestamp, payloadSummary, true)
                }
                
                response.close()
            }
        })
    }
}



