package com.hirenq.tmmrelay.util

import android.content.Context
import android.content.SharedPreferences

object SettingsUtil {
    private const val PREFS_NAME = "tmm_relay_prefs"
    private const val KEY_USE_CATALYST = "use_catalyst"
    
    // Default to WebSocket for backward compatibility
    private const val DEFAULT_USE_CATALYST = false

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun useCatalyst(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_USE_CATALYST, DEFAULT_USE_CATALYST)
    }

    fun setUseCatalyst(context: Context, useCatalyst: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_USE_CATALYST, useCatalyst)
            .apply()
    }
}

