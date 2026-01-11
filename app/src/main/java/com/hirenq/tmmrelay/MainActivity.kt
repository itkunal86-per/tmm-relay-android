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

            // Check if TMM is installed before starting
            val tmmPackageName = findInstalledTmmPackage()
            if (tmmPackageName == null) {
                val message = "Trimble Mobile Manager is not installed. Please install TMM from the Play Store and sign in before starting the relay."
                LogCapture.log(android.util.Log.ERROR, "MainActivity", message)
                showErrorAlert("TMM Not Installed", message)
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
        val location =
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

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

        val notifications =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            else true

        return location && bluetoothConnect && bluetoothScan && notifications
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
        
        return locationPermanentlyDenied || bluetoothConnectPermanentlyDenied || bluetoothScanPermanentlyDenied
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
        try {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                statusReceiver,
                IntentFilter(TmmRelayService.ACTION_STATUS_UPDATE)
            )
            LocalBroadcastManager.getInstance(this).registerReceiver(
                diagnosticsReceiver,
                IntentFilter(TmmRelayService.ACTION_DIAGNOSTICS_UPDATE)
            )

            // Request all permissions when app opens for the first time
            if (!hasRequestedPermissions) {
                LogCapture.log(android.util.Log.INFO, "MainActivity", "Requesting permissions on app open (onResume)...")
                ensurePermissions()
                hasRequestedPermissions = true
            }
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
            append(if (isConnected) "✔ Connected\n" else "✘ Not Connected\n")
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
        
        // Also check connection status and show alert if not connected (but no error)
        if (!isConnected && error == null) {
            // Don't show alert immediately, wait a bit to see if connection establishes
            // This is handled by the error state above
        }
    }

    // ---------------- PERMISSIONS ----------------

    private fun ensurePermissions() {
        val required = mutableListOf<String>()
        
        // Always request location permission
        required.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // Request Bluetooth permissions for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
            required.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        // Request notification permission for Android 13+
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

    // ---------------- TMM LOGIN ----------------

    /**
     * Check if TMM is installed by trying multiple known package names and intent resolution.
     * TMM package name varies by region, version, and build type.
     */
    private fun findInstalledTmmPackage(): String? {
        val knownPackages = listOf(
            "com.trimble.tmm",
            "com.trimble.mobilemanager",
            "com.trimble.trimblemobilemanager",
            "com.trimble.tmm.enterprise"
        )

        val pm = packageManager
        
        // Method 1: Try getLaunchIntentForPackage - this is the most reliable since it works for opening the app
        LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Checking known packages using getLaunchIntentForPackage...")
        for (pkg in knownPackages) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    LogCapture.log(android.util.Log.INFO, "MainActivity", "Found TMM package via getLaunchIntentForPackage: $pkg")
                    return pkg
                }
            } catch (e: Exception) {
                LogCapture.log(android.util.Log.DEBUG, "MainActivity", "getLaunchIntentForPackage failed for $pkg: ${e.message}")
            }
        }
        
        // Method 2: Try to resolve the TMM login intent
        try {
            val loginIntent = Intent("com.trimble.tmm.LOGIN").apply {
                putExtra("applicationID", packageName)
                putExtra("receiverName", "Catalyst")
            }
            
            // Try with MATCH_ALL flag first (Android 11+ might need this)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PackageManager.MATCH_ALL or PackageManager.MATCH_DEFAULT_ONLY
            } else {
                PackageManager.MATCH_DEFAULT_ONLY
            }
            
            val resolveInfo = pm.queryIntentActivities(loginIntent, flags)
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Intent resolution found ${resolveInfo.size} activities")
            
            if (resolveInfo.isNotEmpty()) {
                // Get package name from first resolved activity
                val resolvedPackageName = resolveInfo[0].activityInfo.packageName
                LogCapture.log(android.util.Log.INFO, "MainActivity", "Found TMM package via intent resolution: $resolvedPackageName")
                return resolvedPackageName
            }
        } catch (e: Exception) {
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Intent resolution failed: ${e.message}", e)
        }
        
        // Method 3: Try resolveActivity method as alternative
        try {
            val loginIntent = Intent("com.trimble.tmm.LOGIN").apply {
                putExtra("applicationID", packageName)
                putExtra("receiverName", "Catalyst")
            }
            val resolveInfo = pm.resolveActivity(loginIntent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolveInfo != null) {
                val resolvedPackageName = resolveInfo.activityInfo.packageName
                LogCapture.log(android.util.Log.INFO, "MainActivity", "Found TMM package via resolveActivity: $resolvedPackageName")
                return resolvedPackageName
            }
        } catch (e: Exception) {
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "resolveActivity failed: ${e.message}")
        }
        
        // Method 4: Check known package names using getPackageInfo
        LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Checking known package names using getPackageInfo: $knownPackages")
        for (pkg in knownPackages) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                LogCapture.log(android.util.Log.INFO, "MainActivity", "Found TMM package: $pkg")
                return pkg
            } catch (e: PackageManager.NameNotFoundException) {
                LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Package $pkg not found")
                // Continue to next package
            } catch (e: Exception) {
                LogCapture.log(android.util.Log.WARN, "MainActivity", "Error checking package $pkg: ${e.message}")
            }
        }
        
        // Method 5: Search all installed packages for Trimble-related packages
        try {
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Searching all installed packages for Trimble apps...")
            val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
            
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Total installed packages: ${installedPackages.size}")
            val trimblePackages = mutableListOf<String>()
            
            for (packageInfo in installedPackages) {
                val pkgName = packageInfo.packageName.lowercase()
                if (pkgName.contains("trimble")) {
                    trimblePackages.add(packageInfo.packageName)
                    LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Found Trimble package: ${packageInfo.packageName}")
                    // Check if it's TMM-related
                    if (pkgName.contains("tmm") || pkgName.contains("mobile")) {
                        LogCapture.log(android.util.Log.INFO, "MainActivity", "Found TMM package via search: ${packageInfo.packageName}")
                        return packageInfo.packageName
                    }
                }
            }
            
            // If we found any Trimble packages, log them
            if (trimblePackages.isNotEmpty()) {
                LogCapture.log(android.util.Log.INFO, "MainActivity", "Found Trimble packages (but not TMM): $trimblePackages")
            }
        } catch (e: Exception) {
            LogCapture.log(android.util.Log.WARN, "MainActivity", "Error searching installed packages: ${e.message}", e)
        }
        
        LogCapture.log(android.util.Log.WARN, "MainActivity", "TMM package not found by any method")
        return null
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
