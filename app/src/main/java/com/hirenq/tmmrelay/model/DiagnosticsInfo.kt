package com.hirenq.tmmrelay.model

data class DiagnosticsInfo(
    val locationPermission: Boolean,
    val bluetoothPermission: Boolean,
    val receiverConnected: Boolean,
    val receiverBattery: Int?,
    val fixType: String,
    val satellites: Int,
    val hAcc: Double,
    val vAcc: Double,
    val receiverHealth: String
)
