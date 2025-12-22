package com.hirenq.tmmrelay.model

data class TelemetryPayload(
    val tenantId: String,
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val battery: Int,
    val fixType: String,
    val timestamp: String,
    val health: String,
    val horizontalAccuracy:Double,
    val verticalAccuracy:Double,
    val satellites:Int,
    val userId: String? = null,
    val userName: String? = null,
    val userEmail: String? = null
)
