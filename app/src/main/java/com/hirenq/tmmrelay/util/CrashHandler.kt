package com.hirenq.tmmrelay.util

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Global exception handler to catch and log all uncaught exceptions
 * This helps identify runtime crashes
 */
object CrashHandler : Thread.UncaughtExceptionHandler {
    
    private const val TAG = "CrashHandler"
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    
    fun init() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "Crash handler initialized")
    }
    
    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val errorReport = buildString {
                appendLine("=========================================")
                appendLine("CRASH REPORT - $timestamp")
                appendLine("=========================================")
                appendLine("Thread: ${thread.name}")
                appendLine("Exception: ${exception.javaClass.name}")
                appendLine("Message: ${exception.message}")
                appendLine()
                appendLine("Stack Trace:")
                appendLine(getStackTrace(exception))
                appendLine()
                
                // Add cause if present
                var cause = exception.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    appendLine("Caused by: ${cause.javaClass.name}")
                    appendLine("  Message: ${cause.message}")
                    appendLine("  Stack:")
                    appendLine(getStackTrace(cause))
                    appendLine()
                    cause = cause.cause
                    depth++
                }
                appendLine("=========================================")
            }
            
            // Log to logcat
            Log.e(TAG, errorReport)
            
            // Try to write to file (may fail if storage not available)
            try {
                writeToFile(errorReport)
            } catch (e: Exception) {
                Log.w(TAG, "Could not write crash report to file", e)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in crash handler", e)
        } finally {
            // Call default handler
            defaultHandler?.uncaughtException(thread, exception)
        }
    }
    
    private fun getStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }
    
    private fun writeToFile(content: String) {
        // Write to external storage if available
        // This is a simple implementation - in production, use proper file handling
        try {
            val file = File("/sdcard/tmmrelay_crash_${System.currentTimeMillis()}.txt")
            FileWriter(file).use { it.write(content) }
            Log.i(TAG, "Crash report written to: ${file.absolutePath}")
        } catch (e: Exception) {
            // Ignore - file writing is optional
        }
    }
}

