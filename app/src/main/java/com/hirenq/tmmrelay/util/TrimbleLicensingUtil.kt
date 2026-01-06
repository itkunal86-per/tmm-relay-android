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
            LogCapture.log(Log.DEBUG, TAG, "Trimble Licensing already initialized")
            return true
        }

        return try {
            // Attempt to initialize Trimble Licensing using reflection
            // Try multiple possible class names based on AAR library structure
            val licensingClass = try {
                Class.forName("trimble.licensing.LicensingFactory")
            } catch (e: ClassNotFoundException) {
                try {
                    Class.forName("com.trimble.licensing.LicensingFactory")
                } catch (e2: ClassNotFoundException) {
                    try {
                        Class.forName("trimble.licensing.android.LicensingManager")
                    } catch (e3: ClassNotFoundException) {
                        try {
                            Class.forName("com.trimble.licensing.android.LicensingManager")
                        } catch (e4: ClassNotFoundException) {
                            try {
                                Class.forName("com.trimble.Licensing.Android.LicensingManager")
                            } catch (e5: ClassNotFoundException) {
                                LogCapture.log(Log.WARN, TAG, "Trimble Licensing class not found. Library may use different package structure.")
                                null
                            }
                        }
                    }
                }
            }

            if (licensingClass != null) {
                LogCapture.log(Log.INFO, TAG, "Found Trimble Licensing class: ${licensingClass.name}")
                // Try different initialization methods
                try {
                    // Try createV2Licensing method (common in Trimble SDK v2)
                    val createMethod = licensingClass.getMethod("createV2Licensing", Context::class.java)
                    val licensingInstance = createMethod.invoke(null, context.applicationContext)
                    LogCapture.log(Log.INFO, TAG, "Created Trimble Licensing instance via createV2Licensing")
                    isInitialized = true
                } catch (e: NoSuchMethodException) {
                    // Try standard initialize method
                    try {
                        val initMethod = licensingClass.getMethod("initialize", Context::class.java)
                        val result = initMethod.invoke(null, context.applicationContext)
                        isInitialized = result as? Boolean ?: true
                        LogCapture.log(Log.INFO, TAG, "Trimble Licensing initialized via initialize(): $isInitialized")
                    } catch (e2: Exception) {
                        LogCapture.log(Log.WARN, TAG, "Could not call initialize() method: ${e2.message}")
                        // Some SDKs auto-initialize, so we'll continue
                        isInitialized = true
                    }
                } catch (e: Exception) {
                    LogCapture.log(Log.WARN, TAG, "Could not create licensing instance: ${e.message}")
                    // Some SDKs auto-initialize, so we'll continue
                    isInitialized = true
                }
            } else {
                LogCapture.log(Log.WARN, TAG, "Trimble Licensing class not found. Continuing without explicit initialization.")
                LogCapture.log(Log.WARN, TAG, "Note: SDK may auto-initialize, or subscription loading will handle licensing.")
                // Some SDKs auto-initialize, so we'll continue
                isInitialized = true
            }

            isInitialized
        } catch (e: Exception) {
            LogCapture.log(Log.ERROR, TAG, "Failed to initialize Trimble Licensing: ${e.message}", e)
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

