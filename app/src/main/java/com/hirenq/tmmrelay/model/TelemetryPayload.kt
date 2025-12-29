package com.hirenq.tmmrelay.model

data class TelemetryPayload(
    val tenantId: String,
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val battery: Int, // Mobile device battery
    val fixType: String,
    val timestamp: String,
    val health: String,
    val horizontalAccuracy: Double,
    val verticalAccuracy: Double,
    val satellites: Int,
    val userId: String? = null,
    val userName: String? = null,
    val userEmail: String? = null,
    // Trimble receiver data
    val receiverBattery: Int? = null,
    val receiverHealth: String? = null,
    val pdop: Double? = null,
    val hdop: Double? = null,
    val vdop: Double? = null,
    // Mobile GPS data (always included)
    val mobileLatitude: Double? = null,
    val mobileLongitude: Double? = null,
    val mobileAccuracy: Double? = null,
    val mobileBattery: Int? = null, // Same as battery, but explicitly included
    val dataSource: String? = null // "TRIMBLE" or "MOBILE_GPS" to indicate primary data source
)
