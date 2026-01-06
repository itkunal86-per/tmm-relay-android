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
    
    // Handler for periodic TMM login status checks
    private val statusCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusCheckRunnable = object : Runnable {
        override fun run() {
            updateTmmLoginStatus()
            // Check every 2 seconds while activity is active
            statusCheckHandler.postDelayed(this, 2000)
        }
    }

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
        
        // Check TMM login status on startup
        updateTmmLoginStatus()

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

            launchTmmLoginIfNeeded()
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

        binding.btnOpenTmm.setOnClickListener {
            val tmmPackages = listOf(
                "com.trimble.mobilemanager",
                "com.trimble.tmm",
                "com.trimble.trimblemobilemanager",
                "com.trimble.tmm.enterprise"
            )
            
            var opened = false
            for (pkg in tmmPackages) {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    startActivity(intent)
                    opened = true
                    LogCapture.log(android.util.Log.INFO, "MainActivity", "Opened TMM app: $pkg")
                    break
                }
            }
            
            if (!opened) {
                Toast.makeText(
                    this,
                    "Trimble Mobile Manager not found",
                    Toast.LENGTH_SHORT
                ).show()
                LogCapture.log(android.util.Log.WARN, "MainActivity", "Could not open TMM - no package found")
            }
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
            
            // Update TMM login status when activity resumes
            updateTmmLoginStatus()
            
            // Start periodic status checking to detect external TMM login changes
            statusCheckHandler.post(statusCheckRunnable)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try {
            // Stop periodic status checking when activity is paused
            statusCheckHandler.removeCallbacks(statusCheckRunnable)
            
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
        } else {
            binding.tvErrorStatus.visibility = android.view.View.GONE
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

    private fun updateTmmLoginStatus() {
        try {
            // First, try to detect TMM package
            var tmmPackageName = findInstalledTmmPackage()
            
            // If package detection failed, check if we can at least launch the intent
            // If the intent can be launched, TMM is installed even if we can't detect package name
            if (tmmPackageName == null) {
                try {
                    val loginIntent = Intent("com.trimble.tmm.LOGIN").apply {
                        putExtra("applicationID", packageName)
                        putExtra("receiverName", "Catalyst")
                        putExtra("noInstall", false)
                    }
                    val resolveInfo = packageManager.resolveActivity(loginIntent, PackageManager.MATCH_DEFAULT_ONLY)
                    if (resolveInfo != null) {
                        tmmPackageName = resolveInfo.activityInfo.packageName
                        LogCapture.log(android.util.Log.INFO, "MainActivity", "TMM detected via intent resolution in status check: $tmmPackageName")
                    } else {
                        // Intent can't be resolved, but since CatalystClient can launch it,
                        // TMM might still be installed - show "Installed" status without package name
                        LogCapture.log(android.util.Log.DEBUG, "MainActivity", "TMM package not found but intent may work (like in CatalystClient)")
                        binding.tvTmmLoginStatus.text = "TMM: Installed (Package Unknown)"
                        binding.tvTmmLoginStatus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                        binding.tvTmmLoginStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                        return
                    }
                } catch (e: Exception) {
                    LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Intent resolution in status check failed: ${e.message}")
                    // Since CatalystClient works, assume TMM is installed but we can't detect it
                    binding.tvTmmLoginStatus.text = "TMM: Installed (Package Unknown)"
                    binding.tvTmmLoginStatus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                    binding.tvTmmLoginStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                    return
                }
            }
            
            // If still not found after all methods, but CatalystClient works, show "Installed" anyway
            if (tmmPackageName == null) {
                LogCapture.log(android.util.Log.DEBUG, "MainActivity", "TMM package not found, but showing 'Installed' since SDK works")
                binding.tvTmmLoginStatus.text = "TMM: Installed (Package Unknown)"
                binding.tvTmmLoginStatus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                binding.tvTmmLoginStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                return
            }
            
            // TMM is installed, check login status via SharedPreferences
            val isSignedIn = try {
                // TMM stores login info in its SharedPreferences
                // Common keys: accountTID, accountEmail, userEmail, isLoggedIn
                // Use CONTEXT_IGNORE_SECURITY flag to access TMM's SharedPreferences
                val tmmContext = createPackageContext(tmmPackageName, Context.CONTEXT_IGNORE_SECURITY)
                val prefs = tmmContext.getSharedPreferences("TMM_PREFS", Context.MODE_PRIVATE)
                
                // Check for various possible keys that indicate login
                val accountTID = prefs.getString("accountTID", null)
                val accountEmail = prefs.getString("accountEmail", null)
                val userEmail = prefs.getString("userEmail", null)
                val isLoggedIn = prefs.getBoolean("isLoggedIn", false)
                
                // User is signed in if any of these indicators exist
                val signedIn = !accountTID.isNullOrBlank() || 
                              !accountEmail.isNullOrBlank() || 
                              !userEmail.isNullOrBlank() || 
                              isLoggedIn
                
                LogCapture.log(android.util.Log.DEBUG, "MainActivity", "TMM login check: accountTID=$accountTID, accountEmail=$accountEmail, userEmail=$userEmail, isLoggedIn=$isLoggedIn, result=$signedIn")
                signedIn
            } catch (e: SecurityException) {
                LogCapture.log(android.util.Log.WARN, "MainActivity", "Cannot access TMM SharedPreferences (security): ${e.message}")
                // If we can't access, assume not signed in to be safe
                false
            } catch (e: PackageManager.NameNotFoundException) {
                LogCapture.log(android.util.Log.WARN, "MainActivity", "TMM package context not found: ${e.message}")
                false
            } catch (e: Exception) {
                LogCapture.log(android.util.Log.WARN, "MainActivity", "Error checking TMM login status: ${e.message}", e)
                // On error, show unknown status
                binding.tvTmmLoginStatus.text = "TMM: Installed (Status Unknown)"
                binding.tvTmmLoginStatus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                binding.tvTmmLoginStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                return
            }
            
            // Update UI based on login status
            if (isSignedIn) {
                binding.tvTmmLoginStatus.text = "TMM: Signed In ✓"
                binding.tvTmmLoginStatus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                binding.tvTmmLoginStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            } else {
                binding.tvTmmLoginStatus.text = "TMM: Installed (Not Signed In)"
                binding.tvTmmLoginStatus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                binding.tvTmmLoginStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
        } catch (e: Exception) {
            LogCapture.log(android.util.Log.ERROR, "MainActivity", "Error updating TMM login status: ${e.message}", e)
            binding.tvTmmLoginStatus.text = "TMM: Error"
            binding.tvTmmLoginStatus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.tvTmmLoginStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        }
    }

    /**
     * Launch TMM login Intent from Activity (user action required).
     * This MUST be called from MainActivity, NOT from Service/background thread.
     * Android 10+ blocks Activity launches from background threads.
     */
    private fun launchTmmLoginIfNeeded() {
        // Try to launch TMM login Intent directly - same way as CatalystClient does
        // If the intent launches successfully, TMM is installed (even if we can't detect package name)
        try {
            val intent = Intent("com.trimble.tmm.LOGIN").apply {
                putExtra("applicationID", packageName)
                putExtra("receiverName", "Catalyst")
                putExtra("noInstall", false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            // Try to launch directly - if it works, TMM is installed
            startActivity(intent)
            LogCapture.log(android.util.Log.INFO, "MainActivity", "TMM login Intent launched successfully (TMM is installed)")
            // NOTE: TMM may not show login UI if:
            // - User is already authenticated (this is expected)
            // - TMM version disables forced login
            // - This is expected behavior, not a bug
            
            // Refresh login status after a short delay to allow TMM to update
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                updateTmmLoginStatus()
            }, 2000) // Wait 2 seconds for TMM to update SharedPreferences
        } catch (e: android.content.ActivityNotFoundException) {
            // Intent cannot be resolved - TMM might not be installed
            LogCapture.log(android.util.Log.WARN, "MainActivity", "TMM login Intent not found. TMM may not be installed.")
            Toast.makeText(
                this,
                "TMM not found. Please install Trimble Mobile Manager and sign in",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: SecurityException) {
            LogCapture.log(android.util.Log.WARN, "MainActivity", "Security exception launching TMM login: ${e.message}")
            // Don't show error - continue anyway
        } catch (e: Exception) {
            LogCapture.log(android.util.Log.WARN, "MainActivity", "Failed to launch TMM login: ${e.message}")
            // Don't show error - continue anyway - subscription loading may still work if user is already logged in
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
