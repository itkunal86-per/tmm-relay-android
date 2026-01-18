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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ApiClient {

    private const val TAG = "ApiClient"
    private const val API_URL = "https://altgeo-api.hirenq.com/api/DeviceLog/pushdata"
    private val client: OkHttpClient = OkHttpClient.Builder()
        .build()

    fun send(
        payload: TelemetryPayload, 
        apiKey: String? = null,
        onPostSent: ((String, String, Boolean) -> Unit)? = null
    ) {
        // Ensure Timestamp is in UTC format (with Z suffix) - parse and reformat if needed
        // Use DA2 timestamp if available, else use current timestamp
        val timestamp = if (payload.timestamp != null) {
            try {
                // If payload.timestamp is already in ISO format, use it; otherwise parse and format
                Instant.parse(payload.timestamp).toString()
            } catch (e: Exception) {
                // Fallback to current time in UTC if parsing fails
                Instant.now().toString()
            }
        } else {
            // Use current time in UTC if DA2 timestamp is not available
            Instant.now().toString()
        }

        // Generate CurrentTimestamp in IST (UTC+5:30) format like "2025-12-17T12:45:30+05:30"
        val currentTimestamp = Instant.now()
            .atZone(ZoneId.of("Asia/Kolkata"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

        // Include ALL fields from TelemetryPayload in the POST request
        // Always include all fields with their actual values
        val json = JSONObject().apply {
            // Required fields - ALWAYS include
            put("TenantId", payload.tenantId)
            // Mobile GPS data - ALWAYS include
            put("MobileDeviceId", payload.mobileDeviceId)
            
            // DA2 receiver data (fields 5-17) - nullable, only from DA2 if available
            put("DeviceId", payload.deviceId ?: JSONObject.NULL) // DA2 receiver device ID
            put("Latitude", payload.latitude ?: JSONObject.NULL) // DA2 coordinates
            put("Longitude", payload.longitude ?: JSONObject.NULL) // DA2 coordinates
            put("Battery", payload.battery ?: JSONObject.NULL) // DA2 receiver battery
            put("FixType", payload.fixType ?: JSONObject.NULL) // DA2 fix type
            put("Timestamp", timestamp) // DA2 timestamp (processed above) or current timestamp
            put("CurrentTimestamp", currentTimestamp)
            put("Health", payload.health ?: JSONObject.NULL) // DA2 health
            put("HorizontalAccuracy", payload.horizontalAccuracy ?: JSONObject.NULL) // DA2 horizontal accuracy
            put("VerticalAccuracy", payload.verticalAccuracy ?: JSONObject.NULL) // DA2 vertical accuracy
            put("Satellites", payload.satellites ?: JSONObject.NULL) // DA2 satellites
            
            // Optional user details - always include
            put("UserId", payload.userId ?: JSONObject.NULL)
            put("UserName", payload.userName ?: JSONObject.NULL)
            put("UserEmail", payload.userEmail ?: JSONObject.NULL)
            
            // Mobile GPS data - ALWAYS include
            put("MobileLatitude", payload.mobileLatitude ?: JSONObject.NULL)
            put("MobileLongitude", payload.mobileLongitude ?: JSONObject.NULL)
            put("MobileAccuracy", payload.mobileAccuracy ?: JSONObject.NULL)
            put("MobileBattery", payload.mobileBattery ?: JSONObject.NULL)
            put("MobileBatteryHealth", payload.mobileBatteryHealth ?: JSONObject.NULL)
            put("DataSource", payload.dataSource ?: JSONObject.NULL)
            
            // Survey data - full DA2 survey data as JSON array
            if (payload.surveyData != null) {
                try {
                    put("SurveyData", JSONArray(payload.surveyData))
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing surveyData JSON: ${e.message}", e)
                    put("SurveyData", JSONObject.NULL)
                }
            } else {
                put("SurveyData", JSONObject.NULL)
            }
        }

        val jsonString = json.toString()
        Log.i(TAG, "=== Sending POST request to $API_URL ===")
        Log.i(TAG, "Full payload JSON: $jsonString")
        Log.d(TAG, "Payload fields: TenantId=${payload.tenantId}, MobileDeviceId=${payload.mobileDeviceId}, " +
                "DeviceId=${payload.deviceId}, Lat=${payload.latitude}, Lng=${payload.longitude}, " +
                "Battery=${payload.battery}, FixType=${payload.fixType}, Health=${payload.health}, " +
                "HAcc=${payload.horizontalAccuracy}, VAcc=${payload.verticalAccuracy}, " +
                "Satellites=${payload.satellites}, " +
                "MobileLat=${payload.mobileLatitude}, MobileLng=${payload.mobileLongitude}, " +
                "MobileAcc=${payload.mobileAccuracy}, MobileBattery=${payload.mobileBattery}, " +
                "MobileBatteryHealth=${payload.mobileBatteryHealth}, DataSource=${payload.dataSource}")

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
                    val payloadSummary = "DA2:Lat:${payload.latitude}, Lng:${payload.longitude}, Bat:${payload.battery}%, " +
                            "Mobile:Lat:${payload.mobileLatitude}, Lng:${payload.mobileLongitude}, Bat:${payload.mobileBattery}%"
                    Log.d(TAG, "Invoking onPostSent callback on success: $timestamp - $payloadSummary")
                    onPostSent?.invoke(timestamp, payloadSummary, true)
                }
                
                response.close()
            }
        })
    }
}



