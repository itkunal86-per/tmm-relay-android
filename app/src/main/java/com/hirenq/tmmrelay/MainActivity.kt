package com.hirenq.tmmrelay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hirenq.tmmrelay.databinding.ActivityMainBinding
import com.hirenq.tmmrelay.service.TmmRelayService
import com.hirenq.tmmrelay.util.CrashHandler
import com.hirenq.tmmrelay.util.LogCapture

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private var hasRequestedPermissions = false
    private var lastShownError: String? = null
    
    // Track previous states to detect transitions
    private var previousSubscriptionAvailable: Boolean = false
    private var previousLicenseAvailable: Boolean = false
    private var previousReceiverConnected: Boolean = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Handle permission results
            val allGranted = permissions.all { it.value }
            LogCapture.log(android.util.Log.INFO, "MainActivity", "Permission request completed. All granted: $allGranted")
            if (!allGranted) {
                LogCapture.log(android.util.Log.WARN, "MainActivity", "Some permissions were denied")
            }
        }

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

            val isConnected = intent.getBooleanExtra("isConnected", false)
            val error = intent.getStringExtra("error")

            updateDiagnosticsUI(
                fixType,
                satellites,
                hAcc,
                vAcc,
                receiverHealth,
                receiverBattery,
                isConnected,
                error
            )
        }
    }

    // ---------------- LIFECYCLE ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize crash handler to catch all uncaught exceptions
        CrashHandler.init()
        
        // Check if TMM is installed and visible at startup
        checkTmmInstallation()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
       
        if (!hasAllCriticalPermissions()) {
         ensurePermissions()    
        }    
        updateStatusUI("Stopped", "", "")
        binding.tvDiagnostics.text = "Waiting for diagnostics..."

        binding.btnStart.setOnClickListener {
            // Check if all permissions are granted before starting
            if (!hasAllCriticalPermissions()) {
                LogCapture.log(android.util.Log.WARN, "MainActivity", "Missing critical permissions - cannot start service")
                
                // Check if user has permanently denied permissions (Don't ask again)
                if (shouldRedirectToSettings()) {
                    showPermissionSettingsDialog()
                } else {
                    // Request permissions again if not permanently denied
                    ensurePermissions()
                }
                return@setOnClickListener
            }

            startRelayService()
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, TmmRelayService::class.java))
            updateStatusUI("Stopped", "", "")
            binding.tvDiagnostics.text = "Stopped"
        }

        binding.btnAccessLog.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
    
    // ---------------- ALERT DIALOGS ----------------
    
    private fun showErrorAlert(title: String, message: String) {
        runOnUiThread {
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setCancelable(true)
                .show()
        }
    }

    private fun hasAllCriticalPermissions(): Boolean {
        // Check basic permissions (matching Java demo)
        val bluetoothAdmin =
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_ADMIN
            ) == PackageManager.PERMISSION_GRANTED

        val bluetooth =
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED

        val internet =
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.INTERNET
            ) == PackageManager.PERMISSION_GRANTED

        val networkState =
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_NETWORK_STATE
            ) == PackageManager.PERMISSION_GRANTED

        val location =
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation =
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val readPhoneState =
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

        // Check Android 12+ Bluetooth permissions
        val bluetoothConnect =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            else true

        val bluetoothScan =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            else true

        // Check WRITE_EXTERNAL_STORAGE for Android < S
        val writeStorage =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            else true

        // Check notification permission for Android 13+
        val notifications =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            else true

        return bluetoothAdmin && bluetooth && internet && networkState && 
               location && coarseLocation && readPhoneState && 
               bluetoothConnect && bluetoothScan && writeStorage && notifications
    }
    
    private fun shouldRedirectToSettings(): Boolean {
        // Check if any critical permission was permanently denied (Don't ask again)
        val locationDenied = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
        
        val bluetoothConnectDenied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        } else false

        val bluetoothScanDenied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        } else false

        val readPhoneStateDenied = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) != PackageManager.PERMISSION_GRANTED

        val writeStorageDenied = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        } else false
        
        // If permission is denied and we can't show rationale, user selected "Don't ask again"
        val locationPermanentlyDenied = locationDenied && 
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        
        val bluetoothConnectPermanentlyDenied = bluetoothConnectDenied && 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)
            } else false
        
        val bluetoothScanPermanentlyDenied = bluetoothScanDenied && 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)
            } else false

        val readPhoneStatePermanentlyDenied = readPhoneStateDenied &&
            !shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE)

        val writeStoragePermanentlyDenied = writeStorageDenied &&
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else false
        
        val coarseLocationDenied = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED

        val coarseLocationPermanentlyDenied = coarseLocationDenied &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        return locationPermanentlyDenied || coarseLocationPermanentlyDenied ||
               bluetoothConnectPermanentlyDenied || bluetoothScanPermanentlyDenied || 
               readPhoneStatePermanentlyDenied || writeStoragePermanentlyDenied
    }
    
    private fun showPermissionSettingsDialog() {
        Toast.makeText(
            this,
            "Please allow Location & Bluetooth permissions in Settings",
            Toast.LENGTH_LONG
        ).show()
        
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        } catch (e: Exception) {
            LogCapture.log(android.util.Log.ERROR, "MainActivity", "Failed to open app settings: ${e.message}", e)
            Toast.makeText(
                this,
                "Please enable permissions manually in Settings",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    
    override fun onResume() {
        super.onResume()
        LogCapture.log(android.util.Log.INFO, "MainActivity", "=== onResume() started ===")
        
        try {
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Section 1: Registering status receiver")
            LocalBroadcastManager.getInstance(this).registerReceiver(
                statusReceiver,
                IntentFilter(TmmRelayService.ACTION_STATUS_UPDATE)
            )
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Status receiver registered successfully")
            
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Section 2: Registering diagnostics receiver")
            LocalBroadcastManager.getInstance(this).registerReceiver(
                diagnosticsReceiver,
                IntentFilter(TmmRelayService.ACTION_DIAGNOSTICS_UPDATE)
            )
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Diagnostics receiver registered successfully")

            // Request all permissions when app opens for the first time
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Section 3: Checking if permissions have been requested (hasRequestedPermissions: $hasRequestedPermissions)")
            if (!hasRequestedPermissions) {
                LogCapture.log(android.util.Log.INFO, "MainActivity", "Permissions not yet requested - requesting permissions on app open (onResume)...")
                ensurePermissions()
                hasRequestedPermissions = true
                LogCapture.log(android.util.Log.INFO, "MainActivity", "Permission request initiated, hasRequestedPermissions set to true")
            } else {
                LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Permissions already requested previously, skipping permission request")
            }
            
            // Start service on resume to trigger subscription and license detection
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Section 4: Checking critical permissions before starting service")
            val hasCriticalPermissions = hasAllCriticalPermissions()
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Critical permissions check result: $hasCriticalPermissions")
            
            if (hasCriticalPermissions) {
                LogCapture.log(android.util.Log.INFO, "MainActivity", "All critical permissions granted - starting service for subscription/license detection")
                startRelayService()
                LogCapture.log(android.util.Log.INFO, "MainActivity", "Service start initiated")
            } else {
                LogCapture.log(android.util.Log.WARN, "MainActivity", "Critical permissions missing - cannot start service. Please grant required permissions.")
            }
            
            LogCapture.log(android.util.Log.INFO, "MainActivity", "=== onResume() completed successfully ===")
        } catch (e: Exception) {
            LogCapture.log(android.util.Log.ERROR, "MainActivity", "Error in onResume(): ${e.message}", e)
        }
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
        receiverBattery: Int?,
        isConnected: Boolean,
        error: String?
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
            append(if (isConnected) "✔ Connected\n" else "✘ Not Connected- Survey Not Started\n")
            append("Battery: ${receiverBattery?.let { "$it%" } ?: "N/A"}\n")
            
            // Display error if present
            error?.let {
                append("\nError: $it\n")
            }
            append("\n")

            append("GNSS Fix:\n")
            append("FixType: $fixType\n")
            append("Satellites: $satellites\n")
            append("Accuracy: H ${hAcc.takeIf { it > 0 } ?: "N/A"} m, ")
            append("V ${vAcc.takeIf { it > 0 } ?: "N/A"} m\n")
            append("Health: $receiverHealth")
        }

        binding.tvDiagnostics.text = text
        
        // Update error display if present
        if (error != null) {
            binding.tvErrorStatus.visibility = android.view.View.VISIBLE
            binding.tvErrorStatus.text = "⚠ $error"
            binding.tvErrorStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
            )
            
            // Show alert for errors (but only once per error type)
            if (lastShownError != error) {
                lastShownError = error
                when (error) {
                    "NO_SUBSCRIPTION" -> {
                        val message = "Subscription not found. Please sign in to Trimble Mobile Manager and ensure you have an active subscription."
                        LogCapture.log(android.util.Log.ERROR, "MainActivity", message)
                        showErrorAlert("Subscription Not Found", message)
                    }
                    "NOT_LICENSED" -> {
                        val message = "Device is not licensed. Please check your Trimble Mobile Manager subscription and device licensing."
                        LogCapture.log(android.util.Log.ERROR, "MainActivity", message)
                        showErrorAlert("No Licensing", message)
                    }
                    "CONNECT_FAILED", "DRIVER_INIT_FAILED" -> {
                        val message = "Trimble DA2 device not connected via Bluetooth. Please ensure:\n\n1. Device is powered on\n2. Bluetooth is enabled\n3. Device is paired and connected\n4. TMM app can see the device"
                        LogCapture.log(android.util.Log.ERROR, "MainActivity", message)
                        showErrorAlert("Device Not Connected", message)
                    }
                    "USB connection error", "USB_CONNECTION_ERROR" -> {
                        val message = "USB connection error. Please check the USB connection to the Trimble device."
                        LogCapture.log(android.util.Log.ERROR, "MainActivity", message)
                        showErrorAlert("USB Connection Error", message)
                    }
                    "INIT_FAILED" -> {
                        val message = "Initialization failed. Please check your device connection and try again."
                        LogCapture.log(android.util.Log.ERROR, "MainActivity", message)
                        showErrorAlert("Initialization Failed", message)
                    }
                    else -> {
                        // Check if error message contains keywords for better detection
                        val errorUpper = error.uppercase()
                        when {
                            errorUpper.contains("LICENSE") || errorUpper.contains("NOT_LICENSED") -> {
                                val message = "Device is not licensed. Please check your Trimble Mobile Manager subscription and device licensing."
                                LogCapture.log(android.util.Log.ERROR, "MainActivity", message)
                                showErrorAlert("No Licensing", message)
                            }
                            errorUpper.contains("SUBSCRIPTION") || errorUpper.contains("NO_SUBSCRIPTION") -> {
                                val message = "Subscription not found. Please sign in to Trimble Mobile Manager and ensure you have an active subscription."
                                LogCapture.log(android.util.Log.ERROR, "MainActivity", message)
                                showErrorAlert("Subscription Not Found", message)
                            }
                            errorUpper.contains("CONNECT") || errorUpper.contains("BLUETOOTH") -> {
                                val message = "Trimble DA2 device not connected via Bluetooth. Please ensure:\n\n1. Device is powered on\n2. Bluetooth is enabled\n3. Device is paired and connected\n4. TMM app can see the device"
                                LogCapture.log(android.util.Log.ERROR, "MainActivity", message)
                                showErrorAlert("Device Not Connected", message)
                            }
                            else -> {
                                val message = "Error: $error"
                                LogCapture.log(android.util.Log.ERROR, "MainActivity", message)
                                showErrorAlert("Connection Error", message)
                            }
                        }
                    }
                }
            }
        } else {
            binding.tvErrorStatus.visibility = android.view.View.GONE
            lastShownError = null // Reset when error clears
        }
        
        // Check availability status and show success alerts when they become available
        checkAndShowAvailabilityAlerts(isConnected, error)
    }
    
    private fun checkAndShowAvailabilityAlerts(isConnected: Boolean, error: String?) {
        // Determine current availability states
        val subscriptionAvailable = error != "NO_SUBSCRIPTION" && (error == null || !error.uppercase().contains("SUBSCRIPTION"))
        val licenseAvailable = error != "NOT_LICENSED" && (error == null || !error.uppercase().contains("LICENSE") || !error.uppercase().contains("NOT_LICENSED"))
        val receiverConnected = isConnected
        
        // Check for state transitions and show alerts
        // Subscription became available
        if (subscriptionAvailable && !previousSubscriptionAvailable && error == null) {
            val message = "✅ Subscription is available and active."
            LogCapture.log(android.util.Log.INFO, "MainActivity", message)
            showSuccessAlert("Subscription Available", message)
            previousSubscriptionAvailable = true
        } else if (!subscriptionAvailable) {
            previousSubscriptionAvailable = false
        }
        
        // License became available
        if (licenseAvailable && !previousLicenseAvailable && error == null && isConnected) {
            val message = "✅ License is available and validated."
            LogCapture.log(android.util.Log.INFO, "MainActivity", message)
            showSuccessAlert("License Available", message)
            previousLicenseAvailable = true
        } else if (!licenseAvailable) {
            previousLicenseAvailable = false
        }
        
        // DA2 Receiver became connected
        if (receiverConnected && !previousReceiverConnected && error == null) {
            val message = "✅ Trimble DA2 receiver is connected and available."
            LogCapture.log(android.util.Log.INFO, "MainActivity", message)
            showSuccessAlert("DA2 Receiver Connected", message)
            previousReceiverConnected = true
        } else if (!receiverConnected) {
            previousReceiverConnected = false
        }
    }
    
    private fun showSuccessAlert(title: String, message: String) {
        runOnUiThread {
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setCancelable(true)
                .show()
        }
    }

    // ---------------- PERMISSIONS ----------------

    private fun ensurePermissions() {
        val required = mutableListOf<String>()
        
        // Basic permissions (Android M+ / API 23+) - matching Java demo
        required.add(Manifest.permission.BLUETOOTH_ADMIN)
        required.add(Manifest.permission.BLUETOOTH)
        required.add(Manifest.permission.INTERNET)
        required.add(Manifest.permission.ACCESS_NETWORK_STATE)
        required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        required.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        required.add(Manifest.permission.READ_PHONE_STATE)

        // Request Bluetooth permissions for Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
            required.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // For Android < S (API < 31), add WRITE_EXTERNAL_STORAGE like Java demo
            required.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        // Request notification permission for Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Required permissions: $required")
        LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Missing permissions: $missing")

        if (missing.isNotEmpty()) {
            LogCapture.log(android.util.Log.INFO, "MainActivity", "Launching permission request dialog for: $missing")
            try {
                permissionLauncher.launch(missing.toTypedArray())
            } catch (e: Exception) {
                LogCapture.log(android.util.Log.ERROR, "MainActivity", "Failed to launch permission request: ${e.message}", e)
                // If permission request fails, try redirecting to settings
                showPermissionSettingsDialog()
            }
        } else {
            LogCapture.log(android.util.Log.INFO, "MainActivity", "All permissions already granted")
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

    // ---------------- TMM INSTALLATION CHECK ----------------

    private fun isTmmInstalledAndVisible(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ uses PackageInfoFlags
                ctx.packageManager.getPackageInfo(
                    "com.trimble.trimblemobilemanager",
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                // API < 33 uses int flags
                ctx.packageManager.getPackageInfo(
                    "com.trimble.trimblemobilemanager",
                    0
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun checkTmmInstallation() {
        // Dump all Trimble-related packages
        dumpTrimblePackages(this)
        
        val isInstalled = isTmmInstalledAndVisible(this)
        if (isInstalled) {
            LogCapture.log(android.util.Log.INFO, "MainActivity", "✅ TMM (com.trimble.trimblemobilemanager) is installed and visible")
        } else {
            LogCapture.log(android.util.Log.WARN, "MainActivity", "❌ TMM (com.trimble.trimblemobilemanager) is NOT installed or not visible")
        }
    }
    private fun dumpTrimblePackages(ctx: Context) {
        val pm = ctx.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            .filter { it.packageName.contains("trimble", true) }

        packages.forEach {
           // Log.i(TAG, "Found package: ${it.packageName}")
             LogCapture.log(android.util.Log.INFO, "MainActivity", "Found package: ${it.packageName}")
       
          }
    }
}
