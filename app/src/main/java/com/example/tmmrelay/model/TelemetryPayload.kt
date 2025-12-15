package com.example.tmmrelay.model

data class TelemetryPayload(
    val tenantId: String,
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val battery: Int,
    val fixType: String,
    val timestamp: String,
    val health: String
)
