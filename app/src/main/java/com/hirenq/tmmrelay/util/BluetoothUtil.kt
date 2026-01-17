package com.hirenq.tmmrelay.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object BluetoothUtil {
    private const val PREFS_NAME = "bluetooth_settings"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val TAG = "BluetoothUtil"

    /**
     * Save device name to preferences
     */
    fun saveDeviceName(context: Context, deviceName: String) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEVICE_NAME, deviceName).apply()
        Log.i(TAG, "Saved device name: $deviceName")
    }

    /**
     * Get saved device name from preferences
     */
    fun getDeviceName(context: Context): String? {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_NAME, null)
    }

    /**
     * Save device address to preferences
     */
    fun saveDeviceAddress(context: Context, deviceAddress: String) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEVICE_ADDRESS, deviceAddress).apply()
        Log.i(TAG, "Saved device address: $deviceAddress")
    }

    /**
     * Get saved device address from preferences
     */
    fun getDeviceAddress(context: Context): String? {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ADDRESS, null)
    }

    /**
     * Get Bluetooth MAC address by device name (partial match using contains)
     * Returns the address of the first paired device whose name contains the search string
     */
    @SuppressLint("MissingPermission")
    fun getBluetoothAddressByName(context: Context, deviceName: String): String? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Log.w(TAG, "Bluetooth adapter not available")
            return null
        }

        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is not enabled")
            return null
        }

        val bondedDevices = adapter.bondedDevices ?: run {
            Log.w(TAG, "No bonded devices available")
            return null
        }

        Log.d(TAG, "Searching for device name containing: $deviceName")
        Log.d(TAG, "Found ${bondedDevices.size} paired devices")

        val device = bondedDevices.firstOrNull { bluetoothDevice ->
            val name = bluetoothDevice.name
            val matches = name != null && name.contains(deviceName, ignoreCase = true)
            if (matches) {
                Log.i(TAG, "Found matching device: $name (${bluetoothDevice.address})")
            }
            matches
        }

        return device?.address?.also {
            Log.i(TAG, "DeviceAddress = $it for device name containing: $deviceName")
        }
    }

    /**
     * Get Bluetooth address from saved device name
     * If device name is not set, returns null
     */
    fun getBluetoothAddressFromSavedName(context: Context): String? {
        val deviceName = getDeviceName(context)
        return if (deviceName != null && deviceName.isNotBlank()) {
            getBluetoothAddressByName(context, deviceName)
        } else {
            Log.w(TAG, "No device name saved in preferences")
            null
        }
    }
}

