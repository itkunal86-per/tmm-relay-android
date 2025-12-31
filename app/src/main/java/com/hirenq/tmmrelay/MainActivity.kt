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
            android.util.Log.i("MainActivity", "Permission request completed. All granted: $allGranted")
            if (!allGranted) {
                android.util.Log.w("MainActivity", "Some permissions were denied")
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

        updateStatusUI("Stopped", "", "")
        binding.tvDiagnostics.text = "Waiting for diagnostics..."
        
        // Check TMM login status on startup
        updateTmmLoginStatus()

        binding.btnStart.setOnClickListener {
            // Check if all permissions are granted before starting
            if (!hasAllCriticalPermissions()) {
                android.util.Log.w("MainActivity", "Missing critical permissions - cannot start service")
                
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
            android.util.Log.e("MainActivity", "Failed to open app settings: ${e.message}", e)
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
                android.util.Log.i("MainActivity", "Requesting permissions on app open (onResume)...")
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

        android.util.Log.d("MainActivity", "Required permissions: $required")
        android.util.Log.d("MainActivity", "Missing permissions: $missing")

        if (missing.isNotEmpty()) {
            android.util.Log.i("MainActivity", "Launching permission request dialog for: $missing")
            try {
                permissionLauncher.launch(missing.toTypedArray())
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to launch permission request: ${e.message}", e)
                // If permission request fails, try redirecting to settings
                showPermissionSettingsDialog()
            }
        } else {
            android.util.Log.i("MainActivity", "All permissions already granted")
        }
    }

    // ---------------- TMM LOGIN ----------------

    private fun updateTmmLoginStatus() {
        try {
            val tmmPackageName = "com.trimble.tmm"
            
            // First check if TMM is installed
            val isTmmInstalled = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(tmmPackageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(tmmPackageName, 0)
                }
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Error checking TMM installation for status: ${e.message}")
                false
            }
            
            if (!isTmmInstalled) {
                binding.tvTmmLoginStatus.text = "TMM: Not Installed"
                binding.tvTmmLoginStatus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                binding.tvTmmLoginStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                return
            }
            
            // TMM is installed, check login status via SharedPreferences
            val isSignedIn = try {
                // TMM stores login info in its SharedPreferences
                // Common keys: accountTID, accountEmail, userEmail, isLoggedIn
                val prefs = createPackageContext(tmmPackageName, Context.MODE_PRIVATE)
                    .getSharedPreferences("TMM_PREFS", Context.MODE_PRIVATE)
                
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
                
                android.util.Log.d("MainActivity", "TMM login check: accountTID=$accountTID, accountEmail=$accountEmail, userEmail=$userEmail, isLoggedIn=$isLoggedIn, result=$signedIn")
                signedIn
            } catch (e: SecurityException) {
                android.util.Log.w("MainActivity", "Cannot access TMM SharedPreferences (security): ${e.message}")
                // If we can't access, assume not signed in to be safe
                false
            } catch (e: PackageManager.NameNotFoundException) {
                android.util.Log.w("MainActivity", "TMM package context not found: ${e.message}")
                false
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Error checking TMM login status: ${e.message}", e)
                // On error, show unknown status
                binding.tvTmmLoginStatus.text = "TMM: Unknown"
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
                binding.tvTmmLoginStatus.text = "TMM: Not Signed In"
                binding.tvTmmLoginStatus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                binding.tvTmmLoginStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error updating TMM login status: ${e.message}", e)
            binding.tvTmmLoginStatus.text = "TMM: Error"
            binding.tvTmmLoginStatus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.tvTmmLoginStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        }
    }

    private fun launchTmmLoginIfNeeded() {
        val tmmPackageName = "com.trimble.tmm"
        
        // Check if TMM is installed using multiple methods for reliability
        val isTmmInstalled = try {
            // Method 1: Try to get package info (works on most devices)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(tmmPackageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(tmmPackageName, 0)
                }
                android.util.Log.d("MainActivity", "TMM package found via getPackageInfo")
                true
            } catch (e: PackageManager.NameNotFoundException) {
                // Method 2: Try querying for activities that can handle the Intent
                val intent = Intent("com.trimble.tmm.LOGIN").apply {
                    setPackage(tmmPackageName)
                }
                val activities = packageManager.queryIntentActivities(intent, 0)
                val found = activities.isNotEmpty()
                android.util.Log.d("MainActivity", "TMM check via queryIntentActivities: $found (found ${activities.size} activities)")
                found
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Error checking TMM installation: ${e.message}", e)
            // If all checks fail, assume TMM might be installed and try anyway
            // Don't block the user - let the SDK handle subscription loading
            android.util.Log.i("MainActivity", "Assuming TMM might be installed, will try Intent anyway")
            true // Assume installed to avoid false negatives
        }
        
        android.util.Log.i("MainActivity", "TMM installation check result: $isTmmInstalled")
        
        if (!isTmmInstalled) {
            android.util.Log.w("MainActivity", "TMM app not found. Please install Trimble Mobile Manager from Play Store")
            Toast.makeText(
                this,
                "Please install Trimble Mobile Manager and sign in before starting",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        
        // TMM is installed, try to launch login Intent
        try {
            val intent = Intent("com.trimble.tmm.LOGIN").apply {
                setPackage(tmmPackageName)
                putExtra("applicationID", packageName)
                putExtra("receiverName", "Catalyst")
            }
            startActivity(intent)
            android.util.Log.i("MainActivity", "TMM login Intent launched successfully")
            // NOTE: TMM may not show login UI if:
            // - User is already authenticated (this is expected)
            // - TMM version disables forced login
            // - This is expected behavior, not a bug
            
            // Refresh login status after a short delay to allow TMM to update
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                updateTmmLoginStatus()
            }, 2000) // Wait 2 seconds for TMM to update SharedPreferences
        } catch (e: android.content.ActivityNotFoundException) {
            android.util.Log.w("MainActivity", "TMM login Activity not found - user may already be logged in")
            // Don't show error - TMM might be installed but login activity not available
            // This is OK - SDK will handle subscription loading
        } catch (e: SecurityException) {
            android.util.Log.w("MainActivity", "Security exception launching TMM login: ${e.message}")
            // Don't show error - continue anyway
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to launch TMM login: ${e.message}")
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
