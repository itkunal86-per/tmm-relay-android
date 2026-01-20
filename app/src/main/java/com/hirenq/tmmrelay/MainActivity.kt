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
import java.util.Timer
import java.util.TimerTask

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private var hasRequestedPermissions = false
    private var lastShownError: String? = null
    
    // Track previous states to detect transitions
    private var previousSubscriptionAvailable: Boolean = false
    private var previousLicenseAvailable: Boolean = false
    private var previousReceiverConnected: Boolean = false
    
    // TMM Login state
    private var userTID: String? = null
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("TmmRelayPrefs", Context.MODE_PRIVATE)
    }
    
    // TID Token Refresh state (matching CatalystFacadeActivity.java)
    private val USER_TOKEN_REFRESH_TIME_PERIOD = 5 * 60 * 60 * 1000L // 5 hours
    private var userTokenRefreshTimer: Timer? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Handle permission results (matching demo MainActivity.onRequestPermissionsResult)
            val allGranted = permissions.all { it.value }
            LogCapture.log(android.util.Log.INFO, "MainActivity", "Permission request completed. All granted: $allGranted")
            if (allGranted) {
                // All permissions granted, initialize UI elements (matching demo line 115)
                initUiElements()
            } else {
                Toast.makeText(this, "Permissions required by the application are denied", Toast.LENGTH_LONG).show()
            }
        }
    
    // TMM Login launcher (matching demo MainActivity.onActivityResult REQUEST_LOGIN lines 285-292)
    private val tmmLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Match demo exactly - always try to load subscription, even if login was cancelled
        if (result.resultCode == RESULT_OK && result.data != null) {
            val accountTID = result.data?.getStringExtra("accountTID")
            if (accountTID != null && accountTID.isNotEmpty()) {
                userTID = accountTID
                prefs.edit().putString("userTID", accountTID).apply()
                LogCapture.log(android.util.Log.INFO, "MainActivity", "TMM Login successful - accountTID: $accountTID")
            } else {
                // Login returned but no accountTID - clear userTID
                userTID = null
                prefs.edit().remove("userTID").apply()
                LogCapture.log(android.util.Log.WARN, "MainActivity", "TMM Login returned empty accountTID")
            }
        } else {
            // Login cancelled or failed - clear userTID (matching demo line 290 - passes null)
            userTID = null
            prefs.edit().remove("userTID").apply()
            LogCapture.log(android.util.Log.WARN, "MainActivity", "TMM Login cancelled or failed - resultCode: ${result.resultCode}")
        }
        
        // Always load subscription after login attempt (matching demo - calls beginLoadSubscription regardless)
        val intent = Intent(this, TmmRelayService::class.java).apply {
            action = TmmRelayService.ACTION_LOAD_SUBSCRIPTION
        }
        startService(intent)
        Toast.makeText(this, "Loading subscription...", Toast.LENGTH_SHORT).show()
    }
    
    // TMM Check On Demand launcher (matching demo MainActivity.btnCheckOnDemand)
    private val tmmOnDemandLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val claimCountdown = result.data?.getStringExtra("claimCountdown")
            if (claimCountdown != null) {
                LogCapture.log(android.util.Log.INFO, "MainActivity", "On Demand claim: $claimCountdown")
                Toast.makeText(this, "Claim: $claimCountdown", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // TID Token Refresh launcher (matching CatalystFacadeActivity.java)
    private val tmmRefreshTokenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Token refresh successful
            if (result.data != null) {
                val info = result.data?.getStringExtra("info")
                if (info != null && !info.isEmpty()) {
                    LogCapture.log(android.util.Log.INFO, "MainActivity", "User token refreshed successfully: $info")
                } else {
                    LogCapture.log(android.util.Log.INFO, "MainActivity", "User token refreshed successfully")
                }
            } else {
                LogCapture.log(android.util.Log.INFO, "MainActivity", "User token refreshed successfully")
            }
        } else if (result.resultCode == RESULT_CANCELED) {
            // Token refresh cancelled or failed
            if (result.data != null) {
                val error = result.data?.getStringExtra("error")
                if (error != null && !error.isEmpty()) {
                    LogCapture.log(android.util.Log.WARN, "MainActivity", "User token refresh failed: $error")
                } else {
                    LogCapture.log(android.util.Log.WARN, "MainActivity", "User token refresh cancelled or failed")
                }
            } else {
                LogCapture.log(android.util.Log.WARN, "MainActivity", "User token refresh cancelled or failed")
            }
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

            val isConnected = intent.getBooleanExtra("isConnected", false)
            val error = intent.getStringExtra("error")
            
            // Get DA2 coordinates (TRIMBLE)
            val latitude = if (intent.hasExtra("latitude")) {
                intent.getDoubleExtra("latitude", 0.0)
            } else 0.0
            val longitude = if (intent.hasExtra("longitude")) {
                intent.getDoubleExtra("longitude", 0.0)
            } else 0.0
            
            // Get mobile GPS coordinates
            val mobileLatitude = if (intent.hasExtra("mobileLatitude")) {
                intent.getDoubleExtra("mobileLatitude", 0.0)
            } else 0.0
            val mobileLongitude = if (intent.hasExtra("mobileLongitude")) {
                intent.getDoubleExtra("mobileLongitude", 0.0)
            } else 0.0
            
            // Get data source
            val dataSource = intent.getStringExtra("dataSource") ?: "N/A"
            
            // Get survey status if available
            val surveyStatus = intent.getStringExtra("surveyStatus")
            
            // Get connect/disconnect status if available
            val connectStatus = intent.getStringExtra("connectStatus")
            val disconnectStatus = intent.getStringExtra("disconnectStatus")
            
            // Get load subscription status if available
            val loadSubStatus = intent.getStringExtra("loadSubStatus")
            
            // Get Trimble position data for display (matching demo MainActivity.updatePositionTable)
            val positionSolution = intent.getStringExtra("positionSolution")
            val positionLatitude = intent.getStringExtra("positionLatitude")
            val positionLongitude = intent.getStringExtra("positionLongitude")
            val positionHeight = intent.getStringExtra("positionHeight")
            val positionHPrecision = intent.getStringExtra("positionHPrecision")
            val positionVPrecision = intent.getStringExtra("positionVPrecision")
            val positionCorrectionAge = intent.getStringExtra("positionCorrectionAge")

            updateDiagnosticsUI(
                fixType,
                satellites,
                hAcc,
                vAcc,
                isConnected,
                error,
                latitude,
                longitude,
                mobileLatitude,
                mobileLongitude,
                dataSource
            )
            
            // Update Trimble position data display (matching demo)
            updatePositionData(
                positionSolution,
                positionLatitude,
                positionLongitude,
                positionHeight,
                positionHPrecision,
                positionVPrecision,
                positionCorrectionAge,
                isConnected
            )
            
            // Update button states based on connection status (matching demo)
            // Connect button: enabled when disconnected
            // Disconnect button: enabled when connected
            binding.btnConnect.isEnabled = !isConnected || error != null
            binding.btnDisconnect.isEnabled = isConnected && error == null
            
            // Update Start Survey and End Survey button states based on connection status
            binding.btnStartSurvey.isEnabled = isConnected && error == null
            binding.btnEndSurvey.isEnabled = isConnected && error == null
            
            // Show status messages if available
            surveyStatus?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
            }
            connectStatus?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
            }
            disconnectStatus?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
            }
            loadSubStatus?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- LIFECYCLE ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize crash handler to catch all uncaught exceptions
        CrashHandler.init()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Request permissions first (matching demo MainActivity.onCreate)
        requestPermissions()
    }
    
    /**
     * Request permissions (matching demo MainActivity.requestPermissions)
     */
    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = mutableListOf<String>()
            
            // Basic permissions (matching demo line 67-69)
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_PHONE_STATE
            ))
            
            // Android 12+ permissions (matching demo line 71-73)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.addAll(listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ))
            } else {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            
            // Filter to only request permissions that are not already granted
            val permissionsToRequest = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            
            if (permissionsToRequest.isNotEmpty()) {
                permissionLauncher.launch(permissionsToRequest.toTypedArray())
                return
            }
        }
        
        // All permissions granted, initialize UI elements (matching demo line 85)
        initUiElements()
    }
    
    /**
     * Initialize UI elements (matching demo MainActivity.initUiElements)
     */
    private fun initUiElements() {
        // Check if TMM is installed and visible at startup
        checkTmmInstallation()
        
        updateStatusUI("Stopped", "", "")
        binding.tvDiagnostics.text = "Waiting for diagnostics..."

        // Load Subscription button (matching demo MainActivity.btnLoadSubscription lines 128-143)
        binding.btnLoadSubscription.setOnClickListener {
            // We're using TMM subscription, so launch TMM login Intent first
            try {
                // Match demo exactly - no setPackage(), no FLAG_ACTIVITY_NEW_TASK
                val loginIntent = Intent("com.trimble.tmm.LOGIN").apply {
                    putExtra("applicationID", packageName)
                    // Note: receiverName and noInstall are optional extras in demo
                    // We can add them if needed, but demo doesn't always use them
                }
                
                // Check if Intent can be resolved
                val resolveInfo = packageManager.resolveActivity(loginIntent, PackageManager.MATCH_DEFAULT_ONLY)
                if (resolveInfo == null) {
                    LogCapture.log(android.util.Log.ERROR, "MainActivity", "TMM Login Intent cannot be resolved - TMM may not be installed")
                    android.app.AlertDialog.Builder(this)
                        .setTitle("TMM Relay")
                        .setMessage("Install Trimble Mobile Manager")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setOnClickListener
                }
                
                LogCapture.log(android.util.Log.INFO, "MainActivity", "Launching TMM Login Intent - applicationID: $packageName")
                tmmLoginLauncher.launch(loginIntent)
            } catch (e: android.content.ActivityNotFoundException) {
                LogCapture.log(android.util.Log.ERROR, "MainActivity", "TMM Login Intent failed: ${e.message}", e)
                android.app.AlertDialog.Builder(this)
                    .setTitle("TMM Relay")
                    .setMessage("Install Trimble Mobile Manager")
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                LogCapture.log(android.util.Log.ERROR, "MainActivity", "Unexpected error launching TMM Login: ${e.message}", e)
                Toast.makeText(this, "Error launching TMM Login: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnCheckOnDemand.setOnClickListener {
            // Launch TMM Check On Demand Intent (matching demo MainActivity.btnCheckOnDemand lines 145-154)
            try {
                // Match demo exactly - no setPackage(), no FLAG_ACTIVITY_NEW_TASK
                val onDemandIntent = Intent("com.trimble.tmm.ONDEMAND").apply {
                    putExtra("applicationID", packageName)
                }
                tmmOnDemandLauncher.launch(onDemandIntent)
            } catch (e: android.content.ActivityNotFoundException) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("TMM Relay")
                    .setMessage("Install Trimble Mobile Manager")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        binding.btnStart.setOnClickListener {
            // Start Relay - does NOT connect, just starts telemetry relay service (POST every 5 minutes)
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

        binding.btnStartSurvey.setOnClickListener {
            // Send intent to service to start survey
            val intent = Intent(this, TmmRelayService::class.java).apply {
                action = TmmRelayService.ACTION_START_SURVEY
            }
            startService(intent)
            Toast.makeText(this, "Starting survey...", Toast.LENGTH_SHORT).show()
        }

        binding.btnEndSurvey.setOnClickListener {
            // Send intent to service to end survey (matching demo MainActivity.btnEndSurvey)
            val intent = Intent(this, TmmRelayService::class.java).apply {
                action = TmmRelayService.ACTION_END_SURVEY
            }
            startService(intent)
            Toast.makeText(this, "Ending survey...", Toast.LENGTH_SHORT).show()
        }

        binding.btnConnect.setOnClickListener {
            // Send intent to service to connect to Trimble (matching demo MainActivity.btnConnect)
            val intent = Intent(this, TmmRelayService::class.java).apply {
                action = TmmRelayService.ACTION_CONNECT
            }
            startService(intent)
            Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show()
        }

        binding.btnDisconnect.setOnClickListener {
            // Send intent to service to disconnect from Trimble (matching demo MainActivity.btnDisconnect)
            val intent = Intent(this, TmmRelayService::class.java).apply {
                action = TmmRelayService.ACTION_DISCONNECT
            }
            startService(intent)
            Toast.makeText(this, "Disconnecting...", Toast.LENGTH_SHORT).show()
        }

        binding.btnAccessLog.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }
        
        // Load saved userTID if available
        userTID = prefs.getString("userTID", null)
        if (userTID != null) {
            LogCapture.log(android.util.Log.INFO, "MainActivity", "Loaded saved userTID: $userTID")
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
        
        // Register status update listeners (matching demo MainActivity.onResume line 198)
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver,
            IntentFilter(TmmRelayService.ACTION_STATUS_UPDATE)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            diagnosticsReceiver,
            IntentFilter(TmmRelayService.ACTION_DIAGNOSTICS_UPDATE)
        )
        
        // Start token refresh timer (matching CatalystFacadeActivity.java)
        startTokenRefreshTimer()
        
        // Note: Demo doesn't auto-start service in onResume
        // Service should only start when user clicks "Start Relay" button
    }

    override fun onPause() {
        super.onPause()
        
        // Remove status update listeners (matching demo MainActivity.onPause line 262)
        try {
            LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(statusReceiver)
            LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(diagnosticsReceiver)
        } catch (_: Exception) {}
        
        // Stop token refresh timer (matching CatalystFacadeActivity.java)
        stopTokenRefreshTimer()
    }
    
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.topmainmenu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.menuConfiguration -> {
                startActivity(Intent(this, ConfigurationActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
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
        isConnected: Boolean,
        error: String?,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        mobileLatitude: Double = 0.0,
        mobileLongitude: Double = 0.0,
        dataSource: String = "N/A"
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
            
            // Display error if present
            error?.let {
                append("\nError: $it\n")
            }
            append("\n")

            append("GNSS Fix:\n")
            append("FixType: $fixType\n")
            append("Satellites: $satellites\n")
            append("Accuracy: H ${hAcc.takeIf { it > 0 } ?: "N/A"} m, ")
            append("V ${vAcc.takeIf { it > 0 } ?: "N/A"} m\n\n")
            
            // Display GNSS coordinates with data source
            // Show TRIMBLE coordinates if dataSource is TRIMBLE, otherwise show MOBILE_GPS coordinates
            val displayLat: Double
            val displayLon: Double
            val displaySource: String
            
            when (dataSource.uppercase()) {
                "TRIMBLE", "DA2" -> {
                    displayLat = latitude
                    displayLon = longitude
                    displaySource = "TRIMBLE"
                }
                "MOBILE_GPS" -> {
                    displayLat = mobileLatitude
                    displayLon = mobileLongitude
                    displaySource = "MOBILE_GPS"
                }
                else -> {
                    // Fallback: use whichever has valid coordinates
                    if (latitude != 0.0 || longitude != 0.0) {
                        displayLat = latitude
                        displayLon = longitude
                        displaySource = "TRIMBLE"
                    } else if (mobileLatitude != 0.0 || mobileLongitude != 0.0) {
                        displayLat = mobileLatitude
                        displayLon = mobileLongitude
                        displaySource = "MOBILE_GPS"
                    } else {
                        displayLat = 0.0
                        displayLon = 0.0
                        displaySource = "N/A"
                    }
                }
            }
            
            if (displayLat != 0.0 || displayLon != 0.0) {
                append("GNSS Position (Source: $displaySource):\n")
                append("Lat: ${String.format("%.8f", displayLat)}\n")
                append("Lng: ${String.format("%.8f", displayLon)}")
            } else if (isConnected) {
                // If connected but no coordinates yet, show fix status
                append("GNSS Position:\n")
                append("Connected - Waiting for fix...\n")
                append("FixType: $fixType")
            } else {
                append("GNSS Position:\n")
                append("Waiting for connection...")
            }
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
    
    /**
     * Update Trimble position data display (matching demo MainActivity.updatePositionTable)
     */
    private fun updatePositionData(
        solution: String?,
        latitude: String?,
        longitude: String?,
        height: String?,
        hPrecision: String?,
        vPrecision: String?,
        correctionAge: String?,
        isConnected: Boolean
    ) {
        if (isConnected) {
            // Update position data TextViews
            binding.txtSolutionType.text = solution ?: "-"
            binding.txtLatitude.text = latitude ?: "-"
            binding.txtLongitude.text = longitude ?: "-"
            binding.txtHeight.text = height?.let { "$it m" } ?: "-"
            binding.txtHPrecision.text = hPrecision?.let { "$it m" } ?: "-"
            binding.txtVPrecision.text = vPrecision?.let { "$it m" } ?: "-"
            binding.txtCorrectionAge.text = correctionAge?.let { "$it s" } ?: "-"
        } else {
            // Clear position data when disconnected
            binding.txtSolutionType.text = "-"
            binding.txtLatitude.text = "-"
            binding.txtLongitude.text = "-"
            binding.txtHeight.text = "-"
            binding.txtHPrecision.text = "-"
            binding.txtVPrecision.text = "-"
            binding.txtCorrectionAge.text = "-"
        }
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
            
            // Sensor properties will be logged automatically by TmmRelayService
            // when connection is established (handled in broadcastDiagnostics)
            
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

    // ---------------- TMM LOGIN ----------------
    
    private fun loginViaTMM() {
        try {
            val intent = Intent("com.trimble.tmm.LOGIN").apply {
                putExtra("applicationID", packageName)
                putExtra("noInstall", true)
            }
            tmmLoginLauncher.launch(intent)
            LogCapture.log(android.util.Log.INFO, "MainActivity", "TMM Login intent launched")
        } catch (e: Exception) {
            LogCapture.log(android.util.Log.ERROR, "MainActivity", "Failed to launch TMM Login: ${e.message}", e)
            Toast.makeText(this, "TMM Login failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    // ---------------- TID TOKEN REFRESH (matching CatalystFacadeActivity.java) ----------------
    
    /**
     * Refreshes user token using TMM Intent - "com.trimble.tmm.RefreshUserToken"
     * This intent returns info and error in simple text format via ActivityResultLauncher
     * Called periodically (every 5 hours) to keep TID tokens fresh
     */
    private fun refreshUserToken() {
        try {
            val intent = Intent("com.trimble.tmm.RefreshUserToken").apply {
                putExtra("applicationID", packageName)
            }
            tmmRefreshTokenLauncher.launch(intent)
            LogCapture.log(android.util.Log.DEBUG, "MainActivity", "Started RefreshUserToken intent for applicationID: $packageName")
        } catch (e: Exception) {
            LogCapture.log(android.util.Log.ERROR, "MainActivity", "Failed to start RefreshUserToken intent", e)
        }
    }
    
    /**
     * Starts the token refresh timer (matching CatalystFacadeActivity.java onResume)
     * Refreshes token every 5 hours to keep TID tokens valid
     */
    private fun startTokenRefreshTimer() {
        if (userTokenRefreshTimer == null) {
            userTokenRefreshTimer = Timer()
            userTokenRefreshTimer?.scheduleAtFixedRate(
                object : TimerTask() {
                    override fun run() {
                        // startActivityForResult/ActivityResultLauncher must be called from main thread
                        runOnUiThread {
                            refreshUserToken()
                        }
                    }
                },
                USER_TOKEN_REFRESH_TIME_PERIOD,
                USER_TOKEN_REFRESH_TIME_PERIOD
            )
            LogCapture.log(android.util.Log.INFO, "MainActivity", "Token refresh timer started (period: ${USER_TOKEN_REFRESH_TIME_PERIOD / 1000 / 60} minutes)")
        }
    }
    
    /**
     * Stops the token refresh timer (matching CatalystFacadeActivity.java onStop)
     */
    private fun stopTokenRefreshTimer() {
        userTokenRefreshTimer?.cancel()
        userTokenRefreshTimer = null
        LogCapture.log(android.util.Log.INFO, "MainActivity", "Token refresh timer stopped")
    }

    // ---------------- SERVICE ----------------

    private fun startRelayService() {
        val intent = Intent(this, TmmRelayService::class.java).apply {
            // Pass userTID to service so CatalystClient can use it
            userTID?.let { putExtra("userTID", it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    // ---------------- TMM INSTALLATION CHECK ----------------
    
    /**
     * Find installed TMM package name (matching demo pattern)
     */
    private fun findInstalledTmmPackage(): String? {
        val possiblePackages = listOf(
            "com.trimble.trimblemobilemanager",
            "com.trimble.tmm",
            "com.trimble.mobilemanager"
        )
        
        for (packageName in possiblePackages) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    packageManager.getPackageInfo(packageName, 0)
                }
                return packageName
            } catch (e: Exception) {
                // Package not found, try next
            }
        }
        return null
    }

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
