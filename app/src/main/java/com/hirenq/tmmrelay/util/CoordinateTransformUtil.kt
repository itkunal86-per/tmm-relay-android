package com.hirenq.tmmrelay.util

import android.util.Log

/**
 * Utility class for coordinate transformations using JTDDTransformation library
 * Uses JTDDTransformation-release.aar library
 */
object CoordinateTransformUtil {
    private const val TAG = "CoordinateTransformUtil"
    private var transformationClass: Class<*>? = null

    init {
        // Try to load the transformation class
        transformationClass = try {
            Class.forName("com.trimble.jtdd.transformation.Transformation")
        } catch (e: ClassNotFoundException) {
            try {
                Class.forName("trimble.jtdd.transformation.Transformation")
            } catch (e2: ClassNotFoundException) {
                try {
                    Class.forName("com.jtdd.transformation.Transformation")
                } catch (e3: ClassNotFoundException) {
                    Log.w(TAG, "JTDDTransformation class not found. Coordinate transformation will be unavailable.")
                    null
                }
            }
        }
    }

    /**
     * Transform coordinates from one system to another
     * @param latitude Input latitude (WGS84)
     * @param longitude Input longitude (WGS84)
     * @param targetSystem Target coordinate system (e.g., "UTM", "Indian1975", etc.)
     * @return Pair of transformed coordinates (x, y) or null if transformation fails
     */
    fun transform(
        latitude: Double,
        longitude: Double,
        targetSystem: String = "UTM"
    ): Pair<Double, Double>? {
        if (transformationClass == null) {
            Log.w(TAG, "Transformation library not available")
            return null
        }

        return try {
            // Attempt to use transformation library
            // This is a placeholder - adjust based on actual library API
            val transformMethod = transformationClass!!.getMethod(
                "transform",
                Double::class.java,
                Double::class.java,
                String::class.java
            )
            val result = transformMethod.invoke(null, latitude, longitude, targetSystem)
            
            // Parse result based on library return type
            when (result) {
                is Array<*> -> {
                    if (result.size >= 2) {
                        Pair(result[0] as Double, result[1] as Double)
                    } else null
                }
                is Pair<*, *> -> {
                    Pair(result.first as Double, result.second as Double)
                }
                else -> {
                    Log.w(TAG, "Unexpected transformation result type: ${result?.javaClass}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to transform coordinates", e)
            null
        }
    }

    /**
     * Check if transformation library is available
     */
    fun isAvailable(): Boolean = transformationClass != null
}

