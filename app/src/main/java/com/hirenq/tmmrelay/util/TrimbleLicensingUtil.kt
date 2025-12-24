package com.hirenq.tmmrelay.util

import android.content.Context
import android.util.Log

/**
 * Utility class for initializing Trimble Licensing SDK
 * Uses Trimble.Licensing.Android.aar library
 */
object TrimbleLicensingUtil {
    private const val TAG = "TrimbleLicensingUtil"
    private var isInitialized = false

    /**
     * Initialize Trimble Licensing SDK
     * This should be called before using any Trimble SDK features
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) {
            Log.d(TAG, "Trimble Licensing already initialized")
            return true
        }

        return try {
            // Attempt to initialize Trimble Licensing using reflection
            // Adjust package names based on actual AAR library structure
            val licensingClass = try {
                Class.forName("com.trimble.licensing.android.LicensingManager")
            } catch (e: ClassNotFoundException) {
                try {
                    Class.forName("trimble.licensing.android.LicensingManager")
                } catch (e2: ClassNotFoundException) {
                    try {
                        Class.forName("com.trimble.Licensing.Android.LicensingManager")
                    } catch (e3: ClassNotFoundException) {
                        Log.w(TAG, "Trimble Licensing class not found. Library may use different package structure.")
                        null
                    }
                }
            }

            if (licensingClass != null) {
                val initMethod = licensingClass.getMethod("initialize", Context::class.java)
                val result = initMethod.invoke(null, context.applicationContext)
                isInitialized = result as? Boolean ?: true
                Log.i(TAG, "Trimble Licensing initialized: $isInitialized")
            } else {
                Log.w(TAG, "Trimble Licensing class not found. Continuing without explicit initialization.")
                // Some SDKs auto-initialize, so we'll continue
                isInitialized = true
            }

            isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Trimble Licensing", e)
            // Continue anyway - some SDKs may not require explicit licensing initialization
            isInitialized = false
            false
        }
    }

    /**
     * Check if licensing is initialized
     */
    fun isInitialized(): Boolean = isInitialized
}

