package com.hirenq.tmmrelay

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.ToggleButton
import androidx.annotation.RequiresApi

class BTSelectActivity : Activity() {

    private lateinit var btnShowPaired: Button
    private lateinit var btnScan: ToggleButton
    private lateinit var listBTdevices: ListView
    private var adapter: BluetoothAdapter? = null
    private lateinit var btDevices: ArrayAdapter<BluetoothDevice>
    
    private val REQUEST_ENABLE_BT = 1

    private val onBluetoothDeviceClicked = AdapterView.OnItemClickListener { _, _, position, _ ->
        @SuppressLint("MissingPermission")
        if (adapter?.isDiscovering == true) {
            adapter?.cancelDiscovery()
        }
        val clickedDevice = btDevices.getItem(position)
        val intent = intent
        intent.putExtra(ConfigurationActivity.BLUETOOTH_DEVICE, clickedDevice)
        setResult(RESULT_OK, intent)
        finish()
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        btDevices.add(it)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (btnScan.isChecked) {
                        btnScan.isChecked = false
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    if (!btnScan.isChecked) {
                        btnScan.isChecked = true
                    }
                }
            }
        }
    }

    private val onScanClicked = View.OnClickListener { v ->
        @SuppressLint("MissingPermission")
        val btn = v as ToggleButton
        if (btn.isChecked) {
            if (adapter?.isDiscovering != true) {
                btDevices.clear()
                adapter?.startDiscovery()
            }
        } else {
            if (adapter?.isDiscovering == true) {
                adapter?.cancelDiscovery()
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_btselect)

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        }
        registerReceiver(bluetoothReceiver, filter)

        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        if (adapter?.isEnabled != true) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        } else {
            adapter?.startDiscovery()
        }

        btDevices = object : ArrayAdapter<BluetoothDevice>(this, android.R.layout.simple_list_item_1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? TextView ?: run {
                    LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, null) as TextView
                }
                val device = getItem(position)
                @SuppressLint("MissingPermission")
                val text = String.format("%s  ->  %s", device?.name ?: "Unknown", device?.address ?: "")
                row.text = text
                return row
            }
        }

        findViewById<TextView>(R.id.textViewBTSelect).text = "Select Bluetooth device"

        btnScan = findViewById(R.id.toggleButtonBTSelect)
        listBTdevices = findViewById(R.id.listViewBTSelect)
        btnShowPaired = findViewById(R.id.btnBTPaired)

        listBTdevices.adapter = btDevices
        btnScan.setOnClickListener(onScanClicked)
        btnShowPaired.setOnClickListener { displayBluetoothPaired() }
        listBTdevices.onItemClickListener = onBluetoothDeviceClicked
    }

    @SuppressLint("MissingPermission")
    private fun displayBluetoothPaired() {
        if (adapter?.isDiscovering == true) {
            adapter?.cancelDiscovery()
        }
        btDevices.clear()
        adapter?.bondedDevices?.let { bondedDevices ->
            btDevices.addAll(bondedDevices)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        if (adapter?.isDiscovering == true) {
            adapter?.cancelDiscovery()
        }
        unregisterReceiver(bluetoothReceiver)
    }

    @SuppressLint("MissingPermission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            adapter?.startDiscovery()
        }
    }
}

