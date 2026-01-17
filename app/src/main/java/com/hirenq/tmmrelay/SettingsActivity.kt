package com.hirenq.tmmrelay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hirenq.tmmrelay.databinding.ActivitySettingsBinding
import com.hirenq.tmmrelay.util.BluetoothUtil
import com.hirenq.tmmrelay.util.LogCapture

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                loadPairedDevices()
            } else {
                Toast.makeText(this, "Bluetooth permissions required to list devices", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        // Load saved device name
        val savedDeviceName = BluetoothUtil.getDeviceName(this)
        if (savedDeviceName != null) {
            binding.etDeviceName.setText(savedDeviceName)
            LogCapture.log(android.util.Log.INFO, "SettingsActivity", "Loaded saved device name: $savedDeviceName")
        }

        // Save button
        binding.btnSave.setOnClickListener {
            saveDeviceName()
        }

        // Load paired devices button
        binding.btnLoadPairedDevices.setOnClickListener {
            if (checkBluetoothPermissions()) {
                loadPairedDevices()
            } else {
                requestBluetoothPermissions()
            }
        }
    }

    private fun saveDeviceName() {
        val deviceName = binding.etDeviceName.text.toString().trim()
        if (deviceName.isBlank()) {
            Toast.makeText(this, "Please enter a device name", Toast.LENGTH_SHORT).show()
            return
        }

        BluetoothUtil.saveDeviceName(this, deviceName)
        Toast.makeText(this, "Device name saved: $deviceName", Toast.LENGTH_SHORT).show()
        LogCapture.log(android.util.Log.INFO, "SettingsActivity", "Device name saved: $deviceName")

        // Try to resolve address if permissions are available (just get the address, don't connect)
        var addressResolved = false
        if (checkBluetoothPermissions()) {
            val address = BluetoothUtil.getBluetoothAddressByName(this, deviceName)
            if (address != null) {
                // Save the resolved address to config file so connect() can use it
                BluetoothUtil.saveDeviceAddress(this, address)
                Toast.makeText(this, "Found Bluetooth address: $address", Toast.LENGTH_LONG).show()
                LogCapture.log(android.util.Log.INFO, "SettingsActivity", "Resolved address: $address for device: $deviceName")
                addressResolved = true
            } else {
                Toast.makeText(this, "Device not found in paired devices. Make sure to pair the device first.", Toast.LENGTH_LONG).show()
                LogCapture.log(android.util.Log.WARN, "SettingsActivity", "Could not find device with name containing: $deviceName")
            }
        }
        
        // Only trigger reconnection if address was successfully resolved
        // This will call connect() which will use the resolved address
        if (addressResolved) {
            LogCapture.log(android.util.Log.INFO, "SettingsActivity", "Address resolved. Triggering connect() to establish connection...")
            triggerReconnection()
        } else {
            LogCapture.log(android.util.Log.INFO, "SettingsActivity", "Address not resolved. connect() will be called when device is found or manually triggered.")
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connectGranted = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val scanGranted = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            connectGranted && scanGranted
        } else {
            true
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show()
            return
        }

        if (!adapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        val bondedDevices = adapter.bondedDevices ?: run {
            Toast.makeText(this, "No paired devices found", Toast.LENGTH_SHORT).show()
            binding.tvPairedDevices.text = "No paired devices found"
            return
        }

        val deviceList = StringBuilder()
        deviceList.append("Paired Devices (${bondedDevices.size}):\n\n")
        bondedDevices.forEach { device ->
            val name = device.name ?: "Unknown"
            val address = device.address
            deviceList.append("• $name\n")
            deviceList.append("  Address: $address\n\n")
        }

        binding.tvPairedDevices.text = deviceList.toString()
        LogCapture.log(android.util.Log.INFO, "SettingsActivity", "Loaded ${bondedDevices.size} paired devices")
    }

    private fun triggerReconnection() {
        try {
            val intent = Intent(this, com.hirenq.tmmrelay.service.TmmRelayService::class.java).apply {
                action = com.hirenq.tmmrelay.service.TmmRelayService.ACTION_RECONNECT
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            Toast.makeText(this, "Reconnecting to device...", Toast.LENGTH_SHORT).show()
            LogCapture.log(android.util.Log.INFO, "SettingsActivity", "Reconnection action sent to service")
        } catch (e: Exception) {
            LogCapture.log(android.util.Log.ERROR, "SettingsActivity", "Error triggering reconnection: ${e.message}", e)
            Toast.makeText(this, "Error triggering reconnection: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

