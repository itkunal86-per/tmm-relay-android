package com.hirenq.tmmrelay.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Utility to capture and store login-related logs
 */
object LogCapture {
    private const val MAX_LOG_ENTRIES = 1000
    private val logEntries = ConcurrentLinkedQueue<LogEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    data class LogEntry(
        val timestamp: String,
        val level: String,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    )
    
    /**
     * Capture a log entry related to login process
     */
    fun log(level: Int, tag: String, message: String, throwable: Throwable? = null) {
        val levelName = when (level) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "?"
        }
        
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            level = levelName,
            tag = tag,
            message = message,
            throwable = throwable
        )
        
        // Add to queue
        logEntries.offer(entry)
        
        // Keep only the last MAX_LOG_ENTRIES entries
        while (logEntries.size > MAX_LOG_ENTRIES) {
            logEntries.poll()
        }
        
        // Also log to system logcat
        when (level) {
            Log.VERBOSE -> Log.v(tag, message, throwable)
            Log.DEBUG -> Log.d(tag, message, throwable)
            Log.INFO -> Log.i(tag, message, throwable)
            Log.WARN -> Log.w(tag, message, throwable)
            Log.ERROR -> Log.e(tag, message, throwable)
        }
    }
    
    /**
     * Check if a log should be captured (login-related)
     */
    fun shouldCapture(tag: String, message: String): Boolean {
        val loginKeywords = listOf(
            "login", "Login", "LOGIN",
            "TMM", "tmm",
            "trimble", "Trimble", "TRIMBLE",
            "subscription", "Subscription",
            "licensing", "Licensing",
            "catalyst", "Catalyst", "CATALYST",
            "authentication", "auth",
            "sign in", "signin",
            "package", "Package",
            "MainActivity",
            "CatalystClient",
            "TrimbleLicensingUtil"
        )
        
        val tagLower = tag.lowercase()
        val messageLower = message.lowercase()
        
        return loginKeywords.any { keyword ->
            tagLower.contains(keyword.lowercase()) || messageLower.contains(keyword.lowercase())
        }
    }
    
    /**
     * Get all captured log entries
     */
    fun getAllLogs(): List<LogEntry> {
        return logEntries.toList()
    }
    
    /**
     * Clear all captured logs
     */
    fun clearLogs() {
        logEntries.clear()
    }
    
    /**
     * Get logs as formatted string
     */
    fun getLogsAsString(): String {
        return logEntries.joinToString(separator = "\n") { entry ->
            val throwableStr = if (entry.throwable != null) {
                "\n${Log.getStackTraceString(entry.throwable)}"
            } else {
                ""
            }
            "[${entry.timestamp}] ${entry.level}/${entry.tag}: ${entry.message}$throwableStr"
        }
    }
}

