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
    val horizontalAccuracy:String,
    val verticalAccuracy:String,
    val satellites:String    
)
