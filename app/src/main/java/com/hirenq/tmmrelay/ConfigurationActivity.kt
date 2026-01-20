package com.hirenq.tmmrelay

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hirenq.tmmrelay.databinding.ActivityConfigurationBinding
import com.hirenq.tmmrelay.service.CatalystClient
import com.hirenq.tmmrelay.util.LogCapture
import trimble.jssi.android.catalystfacade.DriverType
import trimble.jssi.android.catalystfacade.TargetReferenceFrame
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

class ConfigurationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigurationBinding
    
    private val CHOOSE_BT_REQUEST = 1
    
    companion object {
        const val BLUETOOTH_DEVICE = "Bluetooth_Device"
        
        // Config property keys (matching CatalystClient.kt and demo MainModel.java)
        private const val CONFIG_KEY_SUBSCRIPTION_TYPE = "SubscriptionType"
        private const val CONFIG_KEY_DRIVER_TYPE = "DriverType"
        private const val CONFIG_KEY_CONNECTION_TYPE = "ConnectionType"
        private const val CONFIG_KEY_DEVICE_ADDRESS = "DeviceAddress"
        private const val CONFIG_KEY_DEVICE_NAME = "DeviceName"
        private const val CONFIG_KEY_DEVICE_PORT_NO = "DevicePortNo"
        private const val CONFIG_KEY_REDUCED_ANTENNA_HEIGHT = "ReducedAntennaHeight"
        private const val CONFIG_KEY_NTRIP_SERVER = "NtripServer"
        private const val CONFIG_KEY_NTRIP_PORT = "NtripPort"
        private const val CONFIG_KEY_NTRIP_USER = "NtripUser"
        private const val CONFIG_KEY_NTRIP_PASSWORD = "NtripPassword"
        private const val CONFIG_KEY_NTRIP_SOURCE = "NtripSource"
        private const val CONFIG_KEY_SURVEY_TYPE = "SurveyType"
        private const val CONFIG_KEY_TARGET_REFERENCE_FRAME = "TargetReferenceFrame"
        private const val CONFIG_KEY_TARGET_REFERENCE_FRAME_ID = "TargetReferenceFrameId"
        private const val CONFIG_KEY_GEOID_GRID_FILE_FULL_PATH = "GeoidGridFileFullPath"
    }
    
    private val configFile: File by lazy {
        File(filesDir.absolutePath + File.separator + "config.properties")
    }
    
    private val btSelectLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            @Suppress("DEPRECATION")
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(BLUETOOTH_DEVICE, BluetoothDevice::class.java)
            } else {
                result.data?.getParcelableExtra<BluetoothDevice>(BLUETOOTH_DEVICE)
            }
            
            device?.let {
                val deviceAddress = it.address
                @SuppressLint("MissingPermission")
                val deviceName = it.name
                binding.edtDeviceAddress.setText(deviceName?.takeIf { name -> name.isNotBlank() } ?: deviceAddress)
                binding.edtDeviceAddress.tag = deviceAddress
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigurationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "${supportActionBar?.title}: Configuration"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        setupSpinners()
        setupListeners()
        loadNtripSourceCache()
        loadConfiguration()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    private fun setupSpinners() {
        // Subscription Type Spinner (simplified - we only use User/TMM subscription)
        val subscriptionTypeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("User"))
        subscriptionTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spnrSubscriptionType.adapter = subscriptionTypeAdapter
        
        // Driver Type Spinner
        val driverTypeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, DriverType.values().map { it.name })
        driverTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spnrDriverType.adapter = driverTypeAdapter
        binding.spnrDriverType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val driverType = DriverType.valueOf(parent?.getItemAtPosition(position).toString())
                updateConnectionTypes(driverType)
                if (driverType == DriverType.TrimbleGNSS || driverType == DriverType.SpectraPrecision) {
                    binding.layoutConnectionType.visibility = View.VISIBLE
                    binding.layoutDevicePort.visibility = View.VISIBLE
                    binding.layoutAddress.visibility = View.VISIBLE
                } else {
                    binding.layoutConnectionType.visibility = View.GONE
                    binding.layoutDevicePort.visibility = View.GONE
                    binding.layoutAddress.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Survey Type Spinner (simplified - we only use TrimbleCorrectionHub)
        val surveyTypeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("TrimbleCorrectionHub"))
        surveyTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spnrSurveyType.adapter = surveyTypeAdapter
        
        // Target Reference Frame Spinner
        val targetRefFrameAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, TargetReferenceFrame.values().map { it.name })
        targetRefFrameAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spnrTargetReferenceFrame.adapter = targetRefFrameAdapter
        
        // NTRIP Source Spinner - will be populated from cache
        val ntripSourceAdapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, mutableListOf("Run Get Ntrip Source List"))
        ntripSourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spnrNtripSource.adapter = ntripSourceAdapter
    }
    
    private fun loadNtripSourceCache() {
        // Load NTRIP source cache (matching demo Configuration.java lines 242-297)
        // For now, just initialize with empty list - can be enhanced later to load from cache file
        val ntripSourceAdapter = binding.spnrNtripSource.adapter as? ArrayAdapter<*>
        if (ntripSourceAdapter != null && ntripSourceAdapter.count == 1) {
            // Already initialized with default message
        }
    }
    
    private fun updateConnectionTypes(driverType: DriverType) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        if (driverType == DriverType.TrimbleGNSS || driverType == DriverType.SpectraPrecision) {
            adapter.add("Bluetooth")
            if (driverType == DriverType.TrimbleGNSS) {
                adapter.add("TcpIp")
            }
            binding.spnrConnType.adapter = adapter
            
            val config = readConfig()
            val connectionType = config?.getProperty(CONFIG_KEY_CONNECTION_TYPE)
            if (connectionType != null) {
                val position = adapter.getPosition(connectionType)
                if (position >= 0) {
                    binding.spnrConnType.setSelection(position)
                }
            }
        }
    }
    
    private fun setupListeners() {
        // Connection Type Spinner
        binding.spnrConnType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val connectionType = parent?.getItemAtPosition(position).toString()
                when (connectionType) {
                    "Bluetooth" -> {
                        binding.layoutDevicePort.visibility = View.GONE
                        binding.btnBthSearch.visibility = View.VISIBLE
                    }
                    "TcpIp" -> {
                        binding.btnBthSearch.visibility = View.GONE
                        binding.layoutDevicePort.visibility = View.VISIBLE
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Device Address EditText - Long click to copy MAC address
        binding.edtDeviceAddress.setOnLongClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val tag = binding.edtDeviceAddress.tag?.toString() ?: ""
            val clip = ClipData.newPlainText(binding.edtDeviceAddress.text, tag)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied $tag to Clipboard", Toast.LENGTH_SHORT).show()
            true
        }
        
        // Device Address Text Watcher - Auto-resolve Bluetooth name from MAC
        binding.edtDeviceAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.edtDeviceAddress.tag = s.toString()
                val selectedConnectionType = binding.spnrConnType.selectedItem?.toString()
                if (binding.edtDeviceAddress.hasFocus() && selectedConnectionType == "Bluetooth") {
                    val deviceAddress = s.toString()
                    if (deviceAddress.matches(Regex("^([0-9A-Fa-f]{2}:){5}([0-9A-Fa-f]{2})$"))) {
                        try {
                            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                            @SuppressLint("MissingPermission")
                            val name = adapter?.getRemoteDevice(deviceAddress)?.name
                            if (!name.isNullOrBlank()) {
                                binding.edtDeviceAddress.setText(name)
                                binding.edtDeviceAddress.tag = deviceAddress
                            }
                        } catch (e: Exception) {
                            LogCapture.log(android.util.Log.WARN, "ConfigurationActivity", "Error getting Bluetooth device name: ${e.message}")
                        }
                    }
                }
            }
        })
        
        // Bluetooth Search Button
        binding.btnBthSearch.setOnClickListener {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                if (!locationManager.isLocationEnabled) {
                    Toast.makeText(this, "Please enable 'Location' to allow bluetooth scanning.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }
            val btSelectIntent = Intent(this, BTSelectActivity::class.java)
            btSelectLauncher.launch(btSelectIntent)
        }
        
        // Geoid File Choose Button (matching demo Configuration.java lines 311-316)
        binding.btnGeoidFileChoose.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            geoidFileLauncher.launch(Intent.createChooser(intent, "Choose a file"))
        }
    }
    
    private val geoidFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    // Get file path from URI
                    val filePath = uri.path
                    binding.edtGeoidGridFileFullPath.setText(filePath)
                } catch (e: Exception) {
                    LogCapture.log(android.util.Log.ERROR, "ConfigurationActivity", "Error getting geoid file path: ${e.message}", e)
                    Toast.makeText(this, "Error selecting file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun loadConfiguration() {
        val config = readConfig() ?: return
        
        // Load Subscription Type
        val subscriptionType = config.getProperty(CONFIG_KEY_SUBSCRIPTION_TYPE) ?: "User"
        val subPosition = (binding.spnrSubscriptionType.adapter as? ArrayAdapter<*>)?.getPosition(subscriptionType) ?: 0
        binding.spnrSubscriptionType.setSelection(subPosition)
        
        // Load Driver Type
        val driverTypeStr = config.getProperty(CONFIG_KEY_DRIVER_TYPE) ?: DriverType.TrimbleGNSS.name
        try {
            val driverType = DriverType.valueOf(driverTypeStr)
            binding.spnrDriverType.setSelection((binding.spnrDriverType.adapter as ArrayAdapter<*>).getPosition(driverType.name))
            updateConnectionTypes(driverType)
        } catch (e: Exception) {
            LogCapture.log(android.util.Log.WARN, "ConfigurationActivity", "Invalid driver type: $driverTypeStr")
        }
        
        // Load Connection Type
        val connectionType = config.getProperty(CONFIG_KEY_CONNECTION_TYPE)
        if (connectionType != null) {
            val connPosition = (binding.spnrConnType.adapter as? ArrayAdapter<*>)?.getPosition(connectionType) ?: 0
            binding.spnrConnType.setSelection(connPosition)
        }
        
        // Load Device Address/Name
        binding.edtDeviceAddress.setText(config.getProperty(CONFIG_KEY_DEVICE_NAME) ?: "")
        binding.edtDeviceAddress.tag = config.getProperty(CONFIG_KEY_DEVICE_ADDRESS) ?: ""
        
        // Load Device Port
        binding.edtDevicePort.setText(config.getProperty(CONFIG_KEY_DEVICE_PORT_NO) ?: "")
        
        // Load NTRIP settings
        binding.edtNtripServer.setText(config.getProperty(CONFIG_KEY_NTRIP_SERVER) ?: "")
        binding.edtNtripPort.setText(config.getProperty(CONFIG_KEY_NTRIP_PORT) ?: "")
        binding.edtNtripUserName.setText(config.getProperty(CONFIG_KEY_NTRIP_USER) ?: "")
        binding.edtNtripPassword.setText(config.getProperty(CONFIG_KEY_NTRIP_PASSWORD) ?: "")
        
        // Load NTRIP Source
        val ntripSource = config.getProperty(CONFIG_KEY_NTRIP_SOURCE)
        if (ntripSource != null && binding.spnrNtripSource.adapter != null) {
            val sourcePosition = (binding.spnrNtripSource.adapter as? ArrayAdapter<*>)?.getPosition(ntripSource) ?: 0
            binding.spnrNtripSource.setSelection(sourcePosition)
        }
        
        // Load Survey Type
        val surveyType = config.getProperty(CONFIG_KEY_SURVEY_TYPE) ?: "TrimbleCorrectionHub"
        val surveyPosition = (binding.spnrSurveyType.adapter as? ArrayAdapter<*>)?.getPosition(surveyType) ?: 0
        binding.spnrSurveyType.setSelection(surveyPosition)
        
        // Load Target Reference Frame
        val targetRefFrame = config.getProperty(CONFIG_KEY_TARGET_REFERENCE_FRAME) ?: TargetReferenceFrame.UseLocalSettings.name
        try {
            val targetRefFramePosition = (binding.spnrTargetReferenceFrame.adapter as? ArrayAdapter<*>)?.getPosition(targetRefFrame) ?: 0
            binding.spnrTargetReferenceFrame.setSelection(targetRefFramePosition)
        } catch (e: Exception) {
            LogCapture.log(android.util.Log.WARN, "ConfigurationActivity", "Invalid target reference frame: $targetRefFrame")
        }
        
        // Load other settings
        binding.edtTargetReferenceFrameId.setText(config.getProperty(CONFIG_KEY_TARGET_REFERENCE_FRAME_ID) ?: "0")
        binding.edtGeoidGridFileFullPath.setText(config.getProperty(CONFIG_KEY_GEOID_GRID_FILE_FULL_PATH) ?: "")
        binding.edtReducedAntennaHght.setText(config.getProperty(CONFIG_KEY_REDUCED_ANTENNA_HEIGHT) ?: "")
    }
    
    private fun readConfig(): Properties? {
        if (!configFile.exists()) {
            return null
        }
        val properties = Properties()
        var fileInputStream: FileInputStream? = null
        try {
            fileInputStream = FileInputStream(configFile)
            properties.load(fileInputStream)
        } catch (e: IOException) {
            LogCapture.log(android.util.Log.ERROR, "ConfigurationActivity", "Error reading config file: ${e.message}", e)
            return null
        } finally {
            try {
                fileInputStream?.close()
            } catch (e: IOException) {
                LogCapture.log(android.util.Log.WARN, "ConfigurationActivity", "Error closing config file: ${e.message}")
            }
        }
        return properties
    }
    
    private fun writeConfig(properties: Properties) {
        var fileOutputStream: FileOutputStream? = null
        try {
            fileOutputStream = FileOutputStream(configFile)
            properties.store(fileOutputStream, "Configuration")
            LogCapture.log(android.util.Log.INFO, "ConfigurationActivity", "Configuration saved to ${configFile.absolutePath}")
        } catch (e: IOException) {
            LogCapture.log(android.util.Log.ERROR, "ConfigurationActivity", "Error writing config file: ${e.message}", e)
        } finally {
            try {
                fileOutputStream?.close()
            } catch (e: IOException) {
                LogCapture.log(android.util.Log.WARN, "ConfigurationActivity", "Error closing config file: ${e.message}")
            }
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.topconfigmenu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menuSave -> {
                val properties = Properties()
                properties.setProperty(CONFIG_KEY_SUBSCRIPTION_TYPE, binding.spnrSubscriptionType.selectedItem.toString())
                properties.setProperty(CONFIG_KEY_DRIVER_TYPE, binding.spnrDriverType.selectedItem.toString())
                
                val selectedConnectionType = binding.spnrConnType.selectedItem?.toString()
                if (selectedConnectionType != null) {
                    properties.setProperty(CONFIG_KEY_CONNECTION_TYPE, selectedConnectionType)
                }
                
                val deviceAddressTag = binding.edtDeviceAddress.tag
                val deviceAddress = if (deviceAddressTag != null && deviceAddressTag.toString().isNotEmpty()) {
                    deviceAddressTag.toString()
                } else {
                    binding.edtDeviceAddress.text.toString()
                }
                properties.setProperty(CONFIG_KEY_DEVICE_ADDRESS, deviceAddress)
                properties.setProperty(CONFIG_KEY_DEVICE_NAME, binding.edtDeviceAddress.text.toString())
                properties.setProperty(CONFIG_KEY_DEVICE_PORT_NO, binding.edtDevicePort.text.toString())
                
                properties.setProperty(CONFIG_KEY_NTRIP_SERVER, binding.edtNtripServer.text.toString())
                properties.setProperty(CONFIG_KEY_NTRIP_PORT, binding.edtNtripPort.text.toString())
                properties.setProperty(CONFIG_KEY_NTRIP_USER, binding.edtNtripUserName.text.toString())
                properties.setProperty(CONFIG_KEY_NTRIP_PASSWORD, binding.edtNtripPassword.text.toString())
                
                val ntripSource = binding.spnrNtripSource.selectedItem?.toString()
                if (ntripSource != null) {
                    properties.setProperty(CONFIG_KEY_NTRIP_SOURCE, ntripSource)
                }
                
                val surveyType = binding.spnrSurveyType.selectedItem?.toString()
                if (surveyType != null) {
                    properties.setProperty(CONFIG_KEY_SURVEY_TYPE, surveyType)
                }
                
                val targetRefFrame = binding.spnrTargetReferenceFrame.selectedItem?.toString()
                if (targetRefFrame != null) {
                    properties.setProperty(CONFIG_KEY_TARGET_REFERENCE_FRAME, targetRefFrame)
                }
                
                properties.setProperty(CONFIG_KEY_TARGET_REFERENCE_FRAME_ID, binding.edtTargetReferenceFrameId.text.toString())
                properties.setProperty(CONFIG_KEY_GEOID_GRID_FILE_FULL_PATH, binding.edtGeoidGridFileFullPath.text.toString())
                properties.setProperty(CONFIG_KEY_REDUCED_ANTENNA_HEIGHT, binding.edtReducedAntennaHght.text.toString())
                
                writeConfig(properties)
                Toast.makeText(this, "Configuration saved.", Toast.LENGTH_LONG).show()
                return true
            }
            R.id.menuClear -> {
                AlertDialog.Builder(this)
                    .setMessage("Do you want to clear the configuration?")
                    .setTitle("Delete Configuration")
                    .setPositiveButton("Yes") { _, _ ->
                        binding.edtNtripServer.setText("")
                        binding.edtDeviceAddress.setText("")
                        binding.edtDevicePort.setText("")
                        binding.edtNtripPort.setText("")
                        binding.edtNtripUserName.setText("")
                        binding.edtNtripPassword.setText("")
                        binding.edtTargetReferenceFrameId.setText("0")
                        binding.edtGeoidGridFileFullPath.setText("")
                        binding.edtReducedAntennaHght.setText("")
                        if (binding.spnrNtripSource.adapter != null && (binding.spnrNtripSource.adapter as ArrayAdapter<*>).count > 0) {
                            binding.spnrNtripSource.setSelection(0)
                        }
                        if (binding.spnrSurveyType.adapter != null && (binding.spnrSurveyType.adapter as ArrayAdapter<*>).count > 0) {
                            binding.spnrSurveyType.setSelection(0)
                        }
                        if (binding.spnrTargetReferenceFrame.adapter != null && (binding.spnrTargetReferenceFrame.adapter as ArrayAdapter<*>).count > 0) {
                            binding.spnrTargetReferenceFrame.setSelection(0)
                        }
                        if (configFile.exists()) {
                            configFile.delete()
                        }
                    }
                    .setNegativeButton("No", null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}

