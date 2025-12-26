package trimble.jssi.android.catalystfacade.catalystfacadedemo;

import android.annotation.SuppressLint;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

import trimble.jssi.android.catalystfacade.CatalystFacadeActivity;
import trimble.jssi.android.catalystfacade.DriverType;
import trimble.jssi.android.catalystfacade.PositionUpdate;
import trimble.jssi.android.catalystfacade.TargetReferenceFrame;
import trimble.jssi.android.catalystfacade.catalystfacadedemo.MainModel.ConnectionTypes;
import trimble.jssi.android.catalystfacade.catalystfacadedemo.MainModel.StatusUpdate;
import trimble.jssi.android.catalystfacade.catalystfacadedemo.MainModel.StatusUpdateListener;
import trimble.jssi.android.catalystfacade.catalystfacadedemo.MainModel.SurveyTypes;
import trimble.jssi.android.catalystfacade.catalystfacadedemo.databinding.ActivityConfigurationBinding;
import trimble.jssi.util.SsiPaths;

//Uses @SuppressLint("MissingPermission") - Permission Checks are already done when starting Main Activity. So Lint warnings are suppressed here.

public class Configuration extends CatalystFacadeActivity implements StatusUpdateListener {

    private static final String TPSDK_LOGS_ZIP = "TpsdkLogs.Zip";
    private static final int EXPORT_LOGS_REQUEST = 45;
    private static final int GEOID_FILE_PICK = 46;
    private Dialog progressDialog;
    
    private final int ChooseBtRequest = 1;    

    public static final String BLUETOOTH_DEVICE = "Bluetooth_Device";

    private ActivityConfigurationBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityConfigurationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setActionBar(binding.toolbar);
        binding.toolbar.setTitle (binding.toolbar.getTitle () + ": Configuration");

        ArrayAdapter<String> SubscriptionTypeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        for (MainModel.SubscriptionTypes SubscriptionType :MainModel.SubscriptionTypes.values()) {
            SubscriptionTypeAdapter.add(SubscriptionType.toString());
        }
        binding.spnrSubscriptionType.setAdapter(SubscriptionTypeAdapter);

        ArrayAdapter<String> DriverTypeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        for (DriverType DriverType : trimble.jssi.android.catalystfacade.DriverType.values()) {
        	DriverTypeAdapter.add(DriverType.toString());
        }

        final Spinner spnrDriverType = binding.spnrDriverType;
        binding.spnrDriverType.setAdapter(DriverTypeAdapter);
        binding.spnrDriverType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				
			    DriverType driverType = DriverType.valueOf((String)spnrDriverType.getSelectedItem());
	            updateConnectionTypes(driverType);
	            if (driverType == DriverType.TrimbleGNSS || driverType == DriverType.SpectraPrecision)
	            {
			        binding.layoutConnectionType.setVisibility(View.VISIBLE);
			        binding.layoutDevicePort.setVisibility(View.VISIBLE);
			        binding.layoutAddress.setVisibility(View.VISIBLE);
                    updateConnectionTypes(driverType);
	            }
	            else
	            {
			        binding.layoutConnectionType.setVisibility(View.GONE);
			        binding.layoutDevicePort.setVisibility(View.GONE);
			        binding.layoutAddress.setVisibility(View.GONE);
	            }
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
				// TODO Auto-generated method stub
				
			}
		});
        
        final Spinner spnrConnectionType = binding.spnrConnType;
        spnrConnectionType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

			    String currentConnectionTypeVal = MainModel.getInstance().readConfig().getProperty(MainModel.ConnectionType);
			    ConnectionTypes currentConnectionType = ConnectionTypes.Bluetooth;
			    if(currentConnectionTypeVal != null)
			        currentConnectionType = ConnectionTypes.valueOf(currentConnectionTypeVal);

				ConnectionTypes connectionType = ConnectionTypes.valueOf((String)spnrConnectionType.getSelectedItem());

                if(currentConnectionType != connectionType) {
                    binding.edtDeviceAddress.setText ("");
                    binding.edtDeviceAddress.setTag("");
                    binding.edtDevicePort.setText ("");
                }
	            switch(connectionType)
	            {
	                case Bluetooth:
                        binding.layoutDevicePort.setVisibility(View.GONE);
                        binding.btnBthSearch.setVisibility(View.VISIBLE);
	                    break;
	                case TcpIp:
                        binding.btnBthSearch.setVisibility(View.GONE);
                        binding.layoutDevicePort.setVisibility(View.VISIBLE);
	                    break;
	            }
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
				// TODO Auto-generated method stub
				
			}
		});

        final EditText textConnectionAddress = binding.edtDeviceAddress;
        textConnectionAddress.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) Configuration.this.getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(textConnectionAddress.getText(), textConnectionAddress.getTag().toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(Configuration.this,"Copied "+textConnectionAddress.getTag()+" to Clipboard",Toast.LENGTH_SHORT).show();
            return true;
        });
        textConnectionAddress.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {

                textConnectionAddress.setTag(s.toString());
                Object selectedItem = spnrConnectionType.getSelectedItem();
                if(selectedItem == null) {
                    return;
                }
                ConnectionTypes connectionType = ConnectionTypes.valueOf((String) selectedItem);
                if(textConnectionAddress.hasFocus() && connectionType == ConnectionTypes.Bluetooth) {
                    String deviceAddress = s.toString();
                    if (deviceAddress.matches("^([0-9A-Fa-f]{2}:){5}([0-9A-Fa-f]{2})$")) {
                        BluetoothAdapter adapter =  ((BluetoothManager) Configuration.this.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
                        @SuppressLint("MissingPermission")
                        String name = adapter.getRemoteDevice(deviceAddress).getName();
                        if(name == null || name.trim().isEmpty()) {
                            return;
                        }
                        textConnectionAddress.setText(name);
                        textConnectionAddress.setTag(deviceAddress);
                    }
                }
            }
        });

        binding.btnBthSearch.setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P)
            {
                android.location.LocationManager locationManager = (android.location.LocationManager)getApplicationContext().getSystemService(LOCATION_SERVICE);
                if (!locationManager.isLocationEnabled())
                {
                    runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Please enable 'Location' to allow bluetooth scanning.", Toast.LENGTH_LONG).show());
                    return;
                }
            }
            Intent btSelectIntent = new Intent(Configuration.this, BTSelect.class);
            startActivityForResult(btSelectIntent, ChooseBtRequest);
        });
        
        final ArrayAdapter<String> surveyTypeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        for (SurveyTypes surveyType : MainModel.SurveyTypes.values()) {
            surveyTypeAdapter.add(surveyType.toString());
        }
        binding.spnrSurveyType.setAdapter(surveyTypeAdapter);

        final ArrayAdapter<String> targetReferenceFrameAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        for (TargetReferenceFrame targetReferenceFrame : TargetReferenceFrame.values()) {
            targetReferenceFrameAdapter.add(targetReferenceFrame.toString());
        }
        binding.spnrTargetReferenceFrame.setAdapter(targetReferenceFrameAdapter);

        binding.txtTpsdkVersion.setText("TPSDK Version:"+MainModel.getInstance().getTpsdkVersion());
        binding.txtDeviceSerialNumber.setText(MainModel.getInstance().getHostSerial());

        binding.btnInstallRTXSubscription.setOnClickListener(v -> {
            String optionCode = binding.edtInstallRTXSubscription.getText().toString();

            if (!optionCode.isEmpty())
            {
                final String error = MainModel.getInstance().installRTXSubscription(optionCode);

                if (error != null && !error.isEmpty())
                {
                    runOnUiThread(() -> Toast.makeText(getApplicationContext(), "error: " + error, Toast.LENGTH_LONG).show());

                    return;
                }
            }

            String subscriptions = MainModel.getInstance().getRTXSubscriptions();

            Toast.makeText(getApplicationContext(), subscriptions, Toast.LENGTH_LONG).show();
        });

        binding.btnMailLog.setOnClickListener(v -> MainModel.getInstance().Maillog(Configuration.this));

        MainModel.getInstance().beginReadMountPointCache(new Action<List<String>>() {
            ArrayAdapter<String> ntripSourceAdapter;

            @Override
            public void run(List<String> item) {

                if (!item.isEmpty()) {
                    ntripSourceAdapter = new ArrayAdapter<>(Configuration.this, android.R.layout.simple_spinner_item, item);
                } else {
                    ntripSourceAdapter = new ArrayAdapter<>(Configuration.this, android.R.layout.simple_spinner_item,
                            new String[]{Configuration.this.getString(R.string.RunGetNtripSourceList)});
                }

                final Properties properties = MainModel.getInstance().readConfig();

                runOnUiThread(() -> {
                    String type = properties.getProperty(MainModel.SubscriptionType);
                    MainModel.SubscriptionTypes subscriptionType;
                    try {
                        if(type == null)
                            subscriptionType = MainModel.SubscriptionTypes.User;
                        else
                            subscriptionType = MainModel.SubscriptionTypes.valueOf(type);
                    } catch (IllegalArgumentException e) {
                        subscriptionType = MainModel.SubscriptionTypes.User; //Default
                    }
                    binding.spnrSubscriptionType.setSelection(subscriptionType.ordinal());

                    type = properties.getProperty(MainModel.DriverType);
                    DriverType driverType;
                    try {
                        driverType = DriverType.valueOf(type);
                    } catch (IllegalArgumentException e) {
                        driverType = DriverType.TrimbleGNSS; //Default
                    }
                    spnrDriverType.setSelection(driverType.ordinal());
                    updateConnectionTypes(driverType);

                    textConnectionAddress.setText(properties.getProperty(MainModel.DeviceName));
                    textConnectionAddress.setTag(properties.getProperty(MainModel.DeviceAddress));
                    binding.edtDevicePort.setText(properties.getProperty(MainModel.DevicePortNo));

                    binding.spnrNtripSource.setAdapter(ntripSourceAdapter);
                    binding.edtNtripServer.setText(properties.getProperty(MainModel.NtripServer));
                    binding.edtNtripPort.setText(properties.getProperty(MainModel.NtripPort));
                    binding.edtNtripUserName.setText(properties.getProperty(MainModel.NtripUser));
                    binding.edtNtripPassword.setText(properties.getProperty(MainModel.NtripPassword));
                    binding.edtTargetReferenceFrameId.setText(properties.getProperty(MainModel.TargetReferenceFrameId));
                    binding.edtGeoidGridFileFullPath.setText(properties.getProperty(MainModel.GeoidGridFileFullPath));
                    binding.edtReducedAntennaHght.setText(properties.getProperty(MainModel.ReducedAntennaHeight));
                    binding.spnrNtripSource.setSelection(ntripSourceAdapter.getPosition(properties.getProperty(MainModel.NtripSource)));
                    binding.spnrSurveyType.setSelection(surveyTypeAdapter.getPosition(properties.getProperty(MainModel.SurveyType)));
                    binding.spnrTargetReferenceFrame.setSelection(targetReferenceFrameAdapter.getPosition(properties.getProperty(MainModel.TargetReferenceFrame)));
                });
            }
        });

        SsiPaths.setContext(this);
        binding.switchEnableLogs.setChecked(MainModel.getInstance().isDriverLogsEnabled());
        binding.switchEnableLogs.setOnCheckedChangeListener((buttonView, isChecked) -> MainModel.getInstance().enableDriverLogs(isChecked));

        binding.btnExportLogs.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, TPSDK_LOGS_ZIP);
            startActivityForResult(intent, EXPORT_LOGS_REQUEST);
        });

        binding.btnGeoidFileChoose.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(Intent.createChooser(intent, "Choose a file"), GEOID_FILE_PICK);
        });
    }


    private void updateConnectionTypes(DriverType driverType)
    {
    	ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        if(driverType== DriverType.TrimbleGNSS || driverType == DriverType.SpectraPrecision) {
            adapter.add(ConnectionTypes.Bluetooth.name());
            if(driverType == DriverType.TrimbleGNSS) {
                adapter.add(ConnectionTypes.TcpIp.name());
            }
            binding.spnrConnType.setAdapter(adapter);
            String connectionType = MainModel.getInstance().readConfig().getProperty(MainModel.ConnectionType);
            if (connectionType != null) {
                binding.spnrConnType.setSelection(adapter.getPosition(connectionType));
            }
        }
    }

    @Override
    public String getAppGuid() {
        return MainModel.AppGuid;
    }

    protected void onResume() {
        MainModel.getInstance().addStatusUpdateListener(this);
        String progressMessage = MainModel.getInstance().getProgressMessage();
        if(progressMessage != null) 
            showProgress(progressMessage);
        else 
            hideProgress();
        super.onResume();
    }

    @Override
    protected void onPause() {
        MainModel.getInstance().removeStatusUpdateListener(this);
        hideProgress();
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.topconfigmenu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menuSave) {
            Properties properties = new Properties();
            properties.setProperty(MainModel.SubscriptionType, (String) binding.spnrSubscriptionType.getSelectedItem());
            properties.setProperty(MainModel.DriverType, (String) binding.spnrDriverType.getSelectedItem());
            String selectedConnectionType = (String) binding.spnrConnType.getSelectedItem();
            if(selectedConnectionType != null)
                properties.setProperty(MainModel.ConnectionType, selectedConnectionType);
            TextView deviceAddressText = binding.edtDeviceAddress;
            Object tag = deviceAddressText.getTag();
            String deviceAddress = deviceAddressText.getText().toString();
            if (tag != null && !tag.toString().isEmpty()) {
                deviceAddress = tag.toString();
            }
            properties.setProperty(MainModel.DeviceAddress, deviceAddress);
            properties.setProperty(MainModel.DeviceName,  deviceAddressText.getText().toString());
            properties.setProperty(MainModel.DevicePortNo, binding.edtDevicePort.getText().toString());
            properties.setProperty(MainModel.NtripServer, binding.edtNtripServer.getText().toString());
            properties.setProperty(MainModel.NtripPort, binding.edtNtripPort.getText().toString());
            properties.setProperty(MainModel.NtripUser, binding.edtNtripUserName.getText().toString());
            properties.setProperty(MainModel.NtripPassword, binding.edtNtripPassword.getText().toString());
            properties.setProperty(MainModel.TargetReferenceFrameId, binding.edtTargetReferenceFrameId.getText().toString());
            properties.setProperty(MainModel.GeoidGridFileFullPath, binding.edtGeoidGridFileFullPath.getText().toString());
            properties.setProperty(MainModel.ReducedAntennaHeight, binding.edtReducedAntennaHght.getText().toString());
            String ntripSource = (String) binding.spnrNtripSource.getSelectedItem();
            if (ntripSource != null)
                properties.setProperty(MainModel.NtripSource, ntripSource);
            
            String surveyType = (String) binding.spnrSurveyType.getSelectedItem();
            if (surveyType != null)
                properties.setProperty(MainModel.SurveyType, surveyType);

            String targetReferenceFrame = (String) binding.spnrTargetReferenceFrame.getSelectedItem();
            if (targetReferenceFrame != null)
                properties.setProperty(MainModel.TargetReferenceFrame, targetReferenceFrame);

            MainModel.getInstance().beginWriteConfig(properties);

            runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Configuration saved.", Toast.LENGTH_LONG).show());
        } else if (item.getItemId() == R.id.menuClear) {
            new Builder(this).setMessage("Do you want to clear the configuration?")
                    .setTitle("Delete Configuration")
                    .setPositiveButton("Yes", (dialog, which) -> {
                        binding.edtNtripServer.setText ("");
                        binding.edtDeviceAddress.setText("");
                        binding.edtDevicePort.setText("");
                        binding.edtNtripServer.setText("");
                        binding.edtNtripPort.setText("");
                        binding.edtNtripUserName.setText("");
                        binding.edtNtripPassword.setText("");
                        binding.edtTargetReferenceFrameId.setText("0");
                        binding.edtGeoidGridFileFullPath.setText("");
                        binding.edtReducedAntennaHght.setText("");
                        binding.spnrNtripSource.setSelection(0);
                        binding.spnrSurveyType.setSelection(0);
                        binding.spnrTargetReferenceFrame.setSelection(0);
                        MainModel.getInstance().beginDeleteConfig();
                    })
                    .setNegativeButton("No", null).show();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK)
        {
        	switch (requestCode)
            {
                case ChooseBtRequest:
                    {
                        @SuppressWarnings("deprecation")
                        BluetoothDevice device = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)?
                           data.getParcelableExtra(Configuration.BLUETOOTH_DEVICE, BluetoothDevice.class):
                                data.getExtras().getParcelable(Configuration.BLUETOOTH_DEVICE);

                        String deviceAddress = device.getAddress();
                        @SuppressLint("MissingPermission")
                        String deviceName = device.getName();
                        binding.edtDeviceAddress.setText(deviceName == null || deviceName.trim().isEmpty() ? deviceAddress : deviceName);
                        binding.edtDeviceAddress.setTag(deviceAddress);
                    }
                    break;
                case EXPORT_LOGS_REQUEST:
                    Thread thread = new Thread(() -> exportLogs(data.getData()));
                    thread.start();
                    break;
                case GEOID_FILE_PICK:
                    {
                        Uri uri = data.getData();
                        if (uri != null) {
                            try {
                                File file = FileUtil.from (this, uri);
                                binding.edtGeoidGridFileFullPath.setText(file.getAbsolutePath());
                            }catch (FileNotFoundException e) {
                                e.printStackTrace ();
                            }catch (IOException e) {
                                e.printStackTrace ();
                            }

                        }

                    }
                    break;
            }
        }
    }

    private void exportLogs(Uri data) {
        try {
            int count= MainModel.getInstance().compressDriverLogs(this.getContentResolver().openFileDescriptor(data,"w"));
            runOnUiThread(() -> new Builder(this).setTitle("CatalystFacadeDemo").setMessage(count + " log files exported.").create().show());

        } catch (Exception e) {
            Log.e("JSsiSampleApp","Exporting logs failed",e);
            runOnUiThread(() -> new Builder(this).setTitle("CatalystFacadeDemo").setMessage("Exporting logs failed").create().show());
        }
    }

    @Override
    public void onLicenseStatusUpdate(StatusUpdate statusUpdate) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onSensorStatusUpdate(StatusUpdate statusUpdate) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onPowerSourceStateUpdate(StatusUpdate powerSourceUpdate) {

    }

    @Override
    public void onSurveyStatusUpdate(StatusUpdate statusUpdate) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onPositionUpdate(PositionUpdate positionUpdate) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onSatelliteSummaryUpdate(MainModel.SatelliteSummaryUpdate summary) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onProgress(final String msg) {
        runOnUiThread(() -> {
            if (msg != null) {
                showProgress(msg);
            } else {
                hideProgress();
            }
        });

    }

    protected void showProgress(String msg) {
        if (progressDialog != null)
            progressDialog.dismiss();
        progressDialog = ProgressBarFactory.createProgressDialog(this,msg);
        progressDialog.show();
    }

    protected void hideProgress() {
        if (progressDialog != null)
            progressDialog.dismiss();
    }

    @Override
    public void onRtkConnectionStatusUpdate(StatusUpdate statusUpdate) {
        // Not used here.
        
    }

    @Override
    public void onSurveyTypeUpdate(StatusUpdate statusUpdate) {
        // Not used here.
    }
}
