package com.hirenq.tmmrelay

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import androidx.appcompat.app.AppCompatActivity
import com.hirenq.tmmrelay.databinding.ActivityLogViewerBinding
import com.hirenq.tmmrelay.util.LogCapture

class LogViewerActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityLogViewerBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Enable scrolling
        binding.tvLogs.movementMethod = ScrollingMovementMethod()
        
        // Load and display logs
        refreshLogs()
        
        // Set up refresh button
        binding.btnRefresh.setOnClickListener {
            refreshLogs()
        }
        
        // Set up clear button
        binding.btnClear.setOnClickListener {
            LogCapture.clearLogs()
            refreshLogs()
        }
    }
    
    private fun refreshLogs() {
        val logs = LogCapture.getAllLogs()
        
        if (logs.isEmpty()) {
            binding.tvLogs.text = "No logs captured yet. Logs will appear here when login process runs."
        } else {
            binding.tvLogs.text = LogCapture.getLogsAsString()
            // Scroll to bottom to show latest logs
            binding.scrollView.post {
                binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh logs when activity resumes
        refreshLogs()
    }
}

