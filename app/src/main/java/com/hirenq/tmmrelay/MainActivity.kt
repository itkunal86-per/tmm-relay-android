package com.hirenq.tmmrelay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hirenq.tmmrelay.databinding.ActivityMainBinding
import com.hirenq.tmmrelay.service.TmmRelayService

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("MainActivity", "Received broadcast: ${intent?.action}")
            if (intent?.action == TmmRelayService.ACTION_STATUS_UPDATE) {
                val status = intent.getStringExtra(TmmRelayService.EXTRA_STATUS) ?: "Stopped"
                val postTimestamp = intent.getStringExtra(TmmRelayService.EXTRA_POST_TIMESTAMP) ?: ""
                val postPayload = intent.getStringExtra(TmmRelayService.EXTRA_POST_PAYLOAD) ?: ""
                
                android.util.Log.d("MainActivity", "Updating UI - Status: $status, Timestamp: $postTimestamp, Payload: $postPayload")
                updateStatusUI(status, postTimestamp, postPayload)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize UI with default values
        updateStatusUI("Stopped", "", "")

        binding.btnStart.setOnClickListener {
            ensurePermissions()
            startRelayService()
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, TmmRelayService::class.java))
            updateStatusUI("Stopped", "", "")
        }
    }

    override fun onResume() {
        super.onResume()
        // Register broadcast receiver
        try {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                statusReceiver,
                IntentFilter(TmmRelayService.ACTION_STATUS_UPDATE)
            )
            android.util.Log.d("MainActivity", "Broadcast receiver registered")
            
            // Request current status from service if it's running
            val serviceIntent = Intent(this, TmmRelayService::class.java)
            startService(serviceIntent) // This will trigger onStartCommand which broadcasts status
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error registering receiver", e)
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister broadcast receiver
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
            android.util.Log.d("MainActivity", "Broadcast receiver unregistered")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error unregistering receiver", e)
        }
    }

    private fun updateStatusUI(status: String, postTimestamp: String, postPayload: String) {
        // Update UI on main thread
        runOnUiThread {
            binding.tvStatus.text = status
            
            val postInfo = if (postTimestamp.isNotEmpty() && postPayload.isNotEmpty()) {
                "$postTimestamp - $postPayload"
            } else if (postTimestamp.isNotEmpty()) {
                postTimestamp
            } else {
                "No POST calls yet"
            }
            binding.tvPostPayload.text = postInfo
            
            // Check if there's a failure in the postPayload
            val isFailure = postPayload.contains("Failed:", ignoreCase = true) || 
                           postPayload.contains("Error", ignoreCase = true)
            
            if (postTimestamp.isNotEmpty() && postPayload.isNotEmpty()) {
                // Show API status
                binding.tvFailureLabel.visibility = android.view.View.VISIBLE
                if (isFailure) {
                    // Show failure status in red
                    binding.tvFailureStatus.text = "$postTimestamp: $postPayload"
                    binding.tvFailureStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    binding.tvFailureStatus.visibility = android.view.View.VISIBLE
                } else {
                    // Show success status (green)
                    binding.tvFailureStatus.text = "$postTimestamp: Success - $postPayload"
                    binding.tvFailureStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                    binding.tvFailureStatus.visibility = android.view.View.VISIBLE
                }
            } else {
                // Hide failure status section if no POST calls yet
                binding.tvFailureLabel.visibility = android.view.View.GONE
                binding.tvFailureStatus.visibility = android.view.View.GONE
            }
            
            android.util.Log.d("MainActivity", "UI updated - Status: $status, POST: $postInfo, Failure: $isFailure")
        }
    }

  private fun ensurePermissions() {
    val required = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    // Android 12+ (Bluetooth runtime permission)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        required += Manifest.permission.BLUETOOTH_CONNECT
    }

    // Android 13+ (Notifications)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        required += Manifest.permission.POST_NOTIFICATIONS
    }

    val missing = required.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    if (missing.isNotEmpty()) {
        permissionLauncher.launch(missing.toTypedArray())
    }
}


    private fun startRelayService() {
        val intent = Intent(this, TmmRelayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }
}
