package com.hirenq.tmmrelay.model

data class TelemetryPayload(
    val tenantId: String,
    val dataSource: String? = null, // "TRIMBLE" or "MOBILE_GPS" to indicate primary data source
    val surveyData: String? = null, // JSON array string containing full DA2 survey data
    
    // DA2 receiver data (fields 5-17) - nullable, only from DA2 receiver if available
    val deviceId: String? = null, // DA2 receiver device ID - nullable
    val latitude: Double = 0.0, // DA2 (Trimble receiver) latitude - defaults to 0.0 if not available
    val longitude: Double = 0.0, // DA2 (Trimble receiver) longitude - defaults to 0.0 if not available
    val battery: Int? = null, // DA2 receiver battery - nullable
    val fixType: String? = null, // DA2 receiver fix type - nullable
    val timestamp: String? = null, // DA2 receiver timestamp - nullable
    val health: String? = null, // DA2 receiver health - nullable
    val horizontalAccuracy: Double = 0.0, // DA2 receiver horizontal accuracy - defaults to 0.0 if not available
    val verticalAccuracy: Double = 0.0, // DA2 receiver vertical accuracy - defaults to 0.0 if not available
    val satellites: Int? = null, // DA2 receiver satellites - nullable
    val userId: String? = null, // DA2 user ID - nullable
    val userName: String? = null, // DA2 user name - nullable
    val userEmail: String? = null, // DA2 user email - nullable
   
    // Mobile GPS data (always included)
    val mobileDeviceId: String, // Mobile device ID - always present
    val mobileLatitude: Double? = null,
    val mobileLongitude: Double? = null,
    val mobileAccuracy: Double? = null,
    val mobileBattery: Int? = null, // Mobile device battery
    val mobileBatteryHealth: String? = null, // Android device battery health (GOOD, DEAD, OVERHEAT, etc.)
  
)
