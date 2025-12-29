package com.hirenq.tmmrelay.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import android.util.Log

class LocationManagerUtil(private val context: Context) {
    
    private val TAG = "LocationManagerUtil"
    private val locationManager: LocationManager? = 
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    
    private var lastKnownLocation: Location? = null
    private var locationListener: LocationListener? = null
    
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun isLocationEnabled(): Boolean {
        return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
               locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }
    
    fun startLocationUpdates(
        minTimeMs: Long = 5000, // 5 seconds
        minDistanceM: Float = 10f, // 10 meters
        onLocationUpdate: (Location) -> Unit
    ) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted - cannot start location updates")
            return
        }
        
        if (locationManager == null) {
            Log.e(TAG, "LocationManager is null")
            return
        }
        
        // Stop any existing listener
        stopLocationUpdates()
        
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastKnownLocation = location
                Log.d(TAG, "Location updated: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}")
                onLocationUpdate(location)
            }
            
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                Log.d(TAG, "Location provider status changed: $provider -> $status")
            }
            
            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "Location provider enabled: $provider")
            }
            
            override fun onProviderDisabled(provider: String) {
                Log.w(TAG, "Location provider disabled: $provider")
            }
        }
        
        try {
            // Try GPS first, then network
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        minTimeMs,
                        minDistanceM,
                        locationListener!!,
                        Looper.getMainLooper()
                    )
                    Log.i(TAG, "Started location updates from provider: $provider")
                    
                    // Get last known location immediately
                    val lastLocation = locationManager.getLastKnownLocation(provider)
                    if (lastLocation != null) {
                        lastKnownLocation = lastLocation
                        onLocationUpdate(lastLocation)
                    }
                    break
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting location updates: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting location updates: ${e.message}", e)
        }
    }
    
    fun stopLocationUpdates() {
        locationListener?.let { listener ->
            try {
                locationManager?.removeUpdates(listener)
                Log.i(TAG, "Stopped location updates")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping location updates: ${e.message}", e)
            }
        }
        locationListener = null
    }
    
    fun getLastKnownLocation(): Location? = lastKnownLocation
    
    fun getLastKnownLatitude(): Double = lastKnownLocation?.latitude ?: 0.0
    
    fun getLastKnownLongitude(): Double = lastKnownLocation?.longitude ?: 0.0
    
    fun getLastKnownAccuracy(): Double = lastKnownLocation?.accuracy?.toDouble() ?: -1.0
}

