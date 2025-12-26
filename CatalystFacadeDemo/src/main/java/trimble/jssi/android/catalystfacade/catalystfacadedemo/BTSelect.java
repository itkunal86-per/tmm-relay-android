package trimble.jssi.android.catalystfacade.catalystfacadedemo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ToggleButton;

//Uses @SuppressLint("MissingPermission") - Permission Checks are already done when starting Main Activity. So Lint warnings are suppressed here.

public class BTSelect extends Activity {

	Button btnShowPaired;
	ToggleButton btnScan;
	ListView listBTdevices;
	BluetoothAdapter adapter;
	ArrayAdapter<BluetoothDevice> btDevices;
	final static int REQUEST_ENABLE_BT = 1;


	private final OnItemClickListener onBluetoothDeviceClicked = new OnItemClickListener() {
		@SuppressLint("MissingPermission")
		@Override
		public void onItemClick(AdapterView<?> arg0, View arg1, int position, long arg3) {
			if(adapter.isDiscovering()){
				adapter.cancelDiscovery();
			}
			BluetoothDevice clickedDevice = btDevices.getItem(position);
			Intent intent = getIntent();
			intent.putExtra(Configuration.BLUETOOTH_DEVICE, clickedDevice);
			setResult(RESULT_OK, intent);
			finish();	
		}
	};

	private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			// When discovery finds a device
			if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				@SuppressWarnings("deprecation")
				BluetoothDevice device = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ?
					intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class) :
					intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				// Add the name and address to an array adapter to show in a ListView
				btDevices.add(device);
			} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				if (btnScan.isChecked()) {
					btnScan.setChecked(false);
				}
			} else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)){
				if(!btnScan.isChecked()){
					btnScan.setChecked(true);
				}
			}
		}
	};

	private final OnClickListener onScanClicked = new OnClickListener() {
		@SuppressLint("MissingPermission")
		@Override
		public void onClick(View v) {
			ToggleButton btn = (ToggleButton) v;
			if (btn.isChecked()) {
				if (!adapter.isDiscovering()) {
					btDevices.clear();
					adapter.startDiscovery();
				}
			} else {
				if (adapter.isDiscovering()) {
					adapter.cancelDiscovery();
				}
			}
		}
	};

	@SuppressLint("MissingPermission")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_btselect);

		IntentFilter filter = new IntentFilter();
		filter.addAction(BluetoothDevice.ACTION_FOUND);
		filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
		filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
		registerReceiver(bluetoothReceiver, filter);

		adapter = ((BluetoothManager) this.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();

		if (!adapter.isEnabled()) {
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
		} else{
			adapter.startDiscovery();
		}

		btDevices = new ArrayAdapter<BluetoothDevice>(this,
				android.R.layout.simple_list_item_1) {
			@Override
			public View getView(int position, View convertView, ViewGroup parent) {
				TextView row = (TextView) convertView;
				if (null == row) {
					LayoutInflater inflater = getLayoutInflater();
					row = (TextView) inflater.inflate(android.R.layout.simple_list_item_1, null);
				}
				BluetoothDevice device = getItem(position);
				String text = String.format("%s  ->  %s", device.getName(), device.getAddress());
				row.setText(text);
				return row;
			}
		};

		((TextView)findViewById(R.id.textViewBTSelect)).setText("Select Bluetooth device");
		
		btnScan = findViewById(R.id.toggleButtonBTSelect);
		listBTdevices = findViewById(R.id.listViewBTSelect);
		btnShowPaired = findViewById(R.id.btnBTPaired);

		listBTdevices.setAdapter(btDevices);
		btnScan.setOnClickListener(onScanClicked);
		btnShowPaired.setOnClickListener(onShowBluetoothPairedClicked);
		listBTdevices.setOnItemClickListener(onBluetoothDeviceClicked);
	}

	private final OnClickListener onShowBluetoothPairedClicked = v -> displayBluetoothPaired();

	@SuppressLint("MissingPermission")
	private void displayBluetoothPaired()
	{
		if (adapter.isDiscovering())
		{
			adapter.cancelDiscovery();
		}

		btDevices.clear();
		btDevices.addAll(adapter.getBondedDevices());
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		//getMenuInflater().inflate(R.menu.activity_btselect, menu);
		return true;
	}

	@SuppressLint("MissingPermission")
	@Override
	protected void onDestroy() {
		super.onDestroy();
		if(adapter.isDiscovering()){
			adapter.cancelDiscovery();
		}
		unregisterReceiver(bluetoothReceiver);
	}
}
