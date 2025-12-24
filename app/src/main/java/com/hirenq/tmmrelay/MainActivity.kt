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
import com.hirenq.tmmrelay.util.SettingsUtil

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    // ---------------- STATUS RECEIVER ----------------

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TmmRelayService.ACTION_STATUS_UPDATE) {
                val status = intent.getStringExtra(TmmRelayService.EXTRA_STATUS) ?: "Stopped"
                val postTimestamp =
                    intent.getStringExtra(TmmRelayService.EXTRA_POST_TIMESTAMP) ?: ""
                val postPayload =
                    intent.getStringExtra(TmmRelayService.EXTRA_POST_PAYLOAD) ?: ""

                updateStatusUI(status, postTimestamp, postPayload)
            }
        }
    }

    // ---------------- DIAGNOSTICS RECEIVER ----------------

    private val diagnosticsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TmmRelayService.ACTION_DIAGNOSTICS_UPDATE) return

            val fixType = intent.getStringExtra("fixType") ?: "UNKNOWN"
            val satellites = intent.getIntExtra("satellites", 0)
            val hAcc = intent.getDoubleExtra("horizontalAccuracy", -1.0)
            val vAcc = intent.getDoubleExtra("verticalAccuracy", -1.0)
            val receiverHealth =
                intent.getStringExtra("receiverHealth") ?: "UNKNOWN"

            val receiverBattery =
                if (intent.hasExtra("receiverBattery"))
                    intent.getIntExtra("receiverBattery", -1)
                else null

            updateDiagnosticsUI(
                fixType,
                satellites,
                hAcc,
                vAcc,
                receiverHealth,
                receiverBattery
            )
        }
    }

    // ---------------- LIFECYCLE ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateStatusUI("Stopped", "", "")
        binding.tvDiagnostics.text = "Waiting for diagnostics..."

        // Load and set the connection type preference
        binding.switchUseCatalyst.isChecked = SettingsUtil.useCatalyst(this)
        binding.switchUseCatalyst.setOnCheckedChangeListener { _, isChecked ->
            SettingsUtil.setUseCatalyst(this, isChecked)
        }

        binding.btnStart.setOnClickListener {
            ensurePermissions()

            if (!hasAllCriticalPermissions()) {
                    return@setOnClickListener
                }

            startRelayService()
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, TmmRelayService::class.java))
            updateStatusUI("Stopped", "", "")
            binding.tvDiagnostics.text = "Stopped"
        }
    }

    private fun hasAllCriticalPermissions(): Boolean {
    val location =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    val bluetooth =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        else true

    val notifications =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        else true

    return location && bluetooth && notifications
}

    
    override fun onResume() {
        super.onResume()
        try {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                statusReceiver,
                IntentFilter(TmmRelayService.ACTION_STATUS_UPDATE)
            )
            LocalBroadcastManager.getInstance(this).registerReceiver(
                diagnosticsReceiver,
                IntentFilter(TmmRelayService.ACTION_DIAGNOSTICS_UPDATE)
            )

            //startService(Intent(this, TmmRelayService::class.java))
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try {
            LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(statusReceiver)
            LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(diagnosticsReceiver)
        } catch (_: Exception) {}
    }

    // ---------------- UI UPDATES ----------------

    private fun updateStatusUI(status: String, postTimestamp: String, postPayload: String) {
        runOnUiThread {
            binding.tvStatus.text = status

            val postInfo =
                if (postTimestamp.isNotEmpty() && postPayload.isNotEmpty())
                    "$postTimestamp - $postPayload"
                else if (postTimestamp.isNotEmpty())
                    postTimestamp
                else
                    "No POST calls yet"

            binding.tvPostPayload.text = postInfo

            val isFailure =
                postPayload.contains("Failed", true) ||
                postPayload.contains("Error", true)

            if (postTimestamp.isNotEmpty()) {
                binding.tvFailureLabel.visibility = android.view.View.VISIBLE
                binding.tvFailureStatus.visibility = android.view.View.VISIBLE
                binding.tvFailureStatus.text =
                    if (isFailure) "❌ $postInfo" else "✅ $postInfo"

                binding.tvFailureStatus.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (isFailure)
                            android.R.color.holo_red_dark
                        else
                            android.R.color.holo_green_dark
                    )
                )
            } else {
                binding.tvFailureLabel.visibility = android.view.View.GONE
                binding.tvFailureStatus.visibility = android.view.View.GONE
            }
        }
    }

    private fun updateDiagnosticsUI(
        fixType: String,
        satellites: Int,
        hAcc: Double,
        vAcc: Double,
        receiverHealth: String,
        receiverBattery: Int?
    ) {
        val locationGranted =
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val bluetoothGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            else true

        val text = buildString {
            append("Permissions:\n")
            append(if (locationGranted) "✔ Location\n" else "✘ Location\n")
            append(if (bluetoothGranted) "✔ Bluetooth\n\n" else "✘ Bluetooth\n\n")

            append("Receiver:\n")
            append(if (satellites > 0) "Connected\n" else "Not Connected\n")
            append("Battery: ${receiverBattery?.let { "$it%" } ?: "N/A"}\n\n")

            append("GNSS Fix:\n")
            append("FixType: $fixType\n")
            append("Satellites: $satellites\n")
            append("Accuracy: H ${hAcc.takeIf { it > 0 } ?: "N/A"} m, ")
            append("V ${vAcc.takeIf { it > 0 } ?: "N/A"} m\n")
            append("Health: $receiverHealth")
        }

        binding.tvDiagnostics.text = text
    }

    // ---------------- PERMISSIONS ----------------

    private fun ensurePermissions() {
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required += Manifest.permission.BLUETOOTH_CONNECT
        }

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

    // ---------------- SERVICE ----------------

    private fun startRelayService() {
        val intent = Intent(this, TmmRelayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }
}
