package trimble.jssi.android.catalystfacade.catalystfacadedemo;

import static java.lang.Math.abs;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Locale;

import trimble.jssi.android.catalystfacade.CatalystFacadeActivity;
import trimble.jssi.android.catalystfacade.DriverReturnCode;
import trimble.jssi.android.catalystfacade.PositionUpdate;
import trimble.jssi.android.catalystfacade.catalystfacadedemo.MainModel.StatusUpdate;
import trimble.jssi.android.catalystfacade.catalystfacadedemo.MainModel.StatusUpdateListener;
import trimble.jssi.android.catalystfacade.catalystfacadedemo.databinding.ActivityMainBinding;
import trimble.jssi.components.core.format.Units;

public class MainActivity extends CatalystFacadeActivity implements StatusUpdateListener{

    private static final int REQUEST_LOGIN = 1;
    private static final int CHECK_ONDEMAND = 2;
    private static final int CHANGE_NTRIP_SETTINGS = 3;
    private static final int PICK_FILE = 4;

    private static final int PERMISSIONS_REQUEST = 1;

    private Dialog progressDialog;

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setActionBar(binding.toolbar);
        requestPermissions();
    }

    private void requestPermissions() {

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ArrayList<String> permissions = checkPermissions(new String[]{Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.BLUETOOTH, Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_PHONE_STATE});

            if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ArrayList<String> permissions_s = checkPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT});
                permissions.addAll(permissions_s);
            }
            else {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }

            if (permissions.size() != 0) {
                String[] array = new String[permissions.size()];
                ActivityCompat.requestPermissions(this, permissions.toArray(array), PERMISSIONS_REQUEST);
                return;
            }
        }
        initUiElements();
    }

    ArrayList<String> checkPermissions(String[] permissions) {
        ArrayList<String> ret = new ArrayList<>();
        for (String permission : permissions) {

            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                ret.add(permission);
            }
        }
        return ret;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST) {// If request is cancelled, the result arrays are empty.
            boolean granted = false;
            if (grantResults.length > 0) {
                granted = true;
                for (int result : grantResults) {
                    if (result == PackageManager.PERMISSION_DENIED) {
                        granted = false;
                        break;
                    }
                }
            }
            if (granted) {
                initUiElements();

            } else {

                Toast.makeText(MainActivity.this, "Permissions required by the application are denied", Toast.LENGTH_LONG).show();
            }

            // other 'case' lines to check for other
            // permissions this app might request
        }
    }

    private void initUiElements() {
        binding.btnLoadSubscription.setOnClickListener(v -> {
            if(MainModel.getInstance().isUsingTMM()) {
                Intent loginIntent = new Intent("com.trimble.tmm.LOGIN");
                loginIntent.putExtra("applicationID", MainModel.AppGuid);
                loginIntent.putExtra("receiverName",MainModel.getInstance().getConfiguredReceiverName());
                loginIntent.putExtra("noInstall", !MainModel.getInstance().isCatalystDA1Selected());
                try {
                    startActivityForResult(loginIntent, REQUEST_LOGIN);
                } catch (ActivityNotFoundException e) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                    builder.setTitle("CatalystFacadeDemo").setMessage("Install Trimble Mobile Manager").show();
                }
            } else {
                MainModel.getInstance().beginLoadSubscription("");
            }
        });

        binding.btnCheckOnDemand.setOnClickListener(v -> {
            Intent onDemandIntent = new Intent("com.trimble.tmm.ONDEMAND");
            onDemandIntent.putExtra("applicationID", MainModel.AppGuid);
            try {
                startActivityForResult(onDemandIntent, CHECK_ONDEMAND);
            } catch (ActivityNotFoundException e) {
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle("CatalystFacadeDemo").setMessage("Install Trimble Mobile Manager").show();
            }
        });

        binding.btnConnect.setOnClickListener(v -> MainModel.getInstance().beginConnect());

        binding.btnDisconnect.setOnClickListener(v -> MainModel.getInstance().beginDisonnect());

        binding.btnChangeCorrectionSourceSettings.setOnClickListener(v -> {
            Intent changeCorrectionSourceSettingsIntent = new Intent("com.trimble.tmm.CORRECTIONSOURCESETTINGS");
            changeCorrectionSourceSettingsIntent.putExtra("applicationID", MainModel.AppGuid);
            try {
                startActivityForResult(changeCorrectionSourceSettingsIntent, CHANGE_NTRIP_SETTINGS);
            } catch (ActivityNotFoundException e) {
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle("CatalystFacadeDemo").setMessage("Install Trimble Mobile Manager").show();
            }
        });

        binding.btnGetNtripSourceList.setOnClickListener(v -> MainModel.getInstance().beginGetNtripSource());

        binding.btnStartSurvey.setOnClickListener(v -> {
            binding.switchGoStatic.setChecked(false);
            MainModel.getInstance().beginStartSurvey();
        });

        binding.btnEndSurvey.setOnClickListener(v -> MainModel.getInstance().beginEndSurvey());

        binding.btnMarkPoints.setOnClickListener(v -> showInputPointNameDialog());

        binding.btnClearMarking.setOnClickListener(v -> {
            MainModel.getInstance().clearMarking();
            binding.txtMarker.setText("");
        });

        binding.switchGoStatic.setOnCheckedChangeListener((buttonView, isChecked) -> MainModel.getInstance().goStatic(buttonView.isChecked()));
    }

    @Override
    public String getAppGuid() {
        return MainModel.AppGuid;
    }

    @Override
    protected void onResume() {

        MainModel.getInstance().addStatusUpdateListener(this);
        MainModel.getInstance().ConfigureLoadUserSelectFileAction(new Func<String>() {
            @Override
            public String run() {
                return loadUserSelectedFile();
            }
        });

        String progressMessage = MainModel.getInstance().getProgressMessage();
        if (progressMessage != null)
            showProgress(progressMessage);
        else {
            hideProgress();
        }

        StatusUpdate licStatusUpdate = MainModel.getInstance().getLicStatusUpdate();
        updateStatus(binding.layoutLicStatus, binding.txtLicStatus, licStatusUpdate);

        StatusUpdate claimStatusUpdate = MainModel.getInstance().getClaimStatusUpdate();
        updateStatus(binding.layoutLicStatus, binding.txtLicStatus, claimStatusUpdate);


        StatusUpdate powerSourceUpdate = MainModel.getInstance().getPowerSourceUpdate();
        String batteryStatus = "Unknown";
        if (powerSourceUpdate != null && powerSourceUpdate.getStatus() != null) {
            batteryStatus = powerSourceUpdate.getStatus();
        }
        binding.txtExternalReceiverBatteryLevelValue.setText(batteryStatus);

        StatusUpdate sensorStatusUpdate = MainModel.getInstance().getSensorStatusUpdate();
        updateStatus(binding.layoutSensorStatus, binding.txtSensorStatus, sensorStatusUpdate);

        StatusUpdate surveyStatusUpdate = MainModel.getInstance().getSurveyStatusUpdate();
        updateStatus(binding.layoutSurveyStatus, binding.txtSurveyStatus, surveyStatusUpdate);

        String pointName = MainModel.getInstance().getPointName();
        if (pointName != null)
            binding.txtMarker.setText(String.format("Points are Marked as: %s", pointName));

        StatusUpdate rtkConnectionStatusUpdate = MainModel.getInstance().getRtkConnectionStatusUpdate();
        String status = "";
        if (rtkConnectionStatusUpdate != null && rtkConnectionStatusUpdate.getStatus() != null) {
            status = rtkConnectionStatusUpdate.getStatus();
        }
        binding.txtRtkConnectionStatusValue.setText(status);

        StatusUpdate surveyTypeUpdate = MainModel.getInstance().getSurveyTypeUpdate();
        String surveyType = "";
        if (surveyTypeUpdate != null && surveyTypeUpdate.getStatus() != null) {
            surveyType = surveyTypeUpdate.getStatus();
        }
        binding.txtRtkSurveyTypeValue.setText(surveyType);

        PositionUpdate positionUpdate = MainModel.getInstance().getPositionUpdate();
        if (positionUpdate != null)
            updatePositionTable(positionUpdate);
        else
            resetPositionTable();
        updateSatelliteSummary(MainModel.getInstance().getSatelliteSummaryUpdate());
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
        getMenuInflater().inflate(R.menu.topmainmenu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menuConfiguration) {
            startActivity(new Intent(this, Configuration.class));
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_LOGIN: {
                if (resultCode == RESULT_OK) {
                    String userTID = data.getStringExtra("accountTID");
                    MainModel.getInstance().beginLoadSubscription(userTID);
                } else {
                    MainModel.getInstance().beginLoadSubscription(null);
                }
                break;
            }
            case CHECK_ONDEMAND: {
                if (resultCode == RESULT_OK) {
                    String currentClaim = data.getStringExtra("claimCountdown");
                    MainModel.getInstance().setCurrentClaim(currentClaim);
                }
                break;
            }
            case PICK_FILE: {
                String fileData = "";
                if (resultCode == RESULT_OK) {
                    if(data != null)
                    {
                        try {
                            Uri uri = data.getData();

                            InputStream in = getContentResolver().openInputStream(uri);
                            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                            fileData = reader.readLine();
                        } catch (FileNotFoundException e) {
                            throw new RuntimeException(e);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                lastLoadedFileData = fileData;
                synchronized (loadUserSelectedFileGuard)
                {
                    loadUserSelectedFileGuard.notify();
                }
                break;
            }
        }
    }

    private void updateStatus(final View layout, final View statusTextView, final StatusUpdate statusUpdate) {
        runOnUiThread(() -> {
            if(statusUpdate == null) {
                layout.setBackground(getDrawable(R.drawable.roundedrectgrey));
                ((TextView) statusTextView).setText(getString(R.string.Status));
                return;
            }
            if (statusUpdate.getReturnCode().getCode() == DriverReturnCode.Success) {
                layout.setBackground(getDrawable(R.drawable.roundedrectgreen));
            } else {
                layout.setBackground(getDrawable(R.drawable.roundedrectred));
            }
            ((TextView) statusTextView)
                    .setText(String.format("%s:%s", statusUpdate.getReturnCode().getCode().toString(), statusUpdate.getStatus()));
        });

    }

    @Override
    public void onLicenseStatusUpdate(final StatusUpdate statusUpdate) {
        updateStatus(binding.layoutLicStatus, binding.txtLicStatus, statusUpdate);
    }

    @Override
    public void onSensorStatusUpdate(StatusUpdate statusUpdate) {
        updateStatus(binding.layoutSensorStatus, binding.txtSensorStatus, statusUpdate);
    }

    @Override
    public void onSurveyStatusUpdate(StatusUpdate statusUpdate) {
        updateStatus(binding.layoutSurveyStatus, binding.txtSurveyStatus, statusUpdate);
    }

    @Override
    public void onPositionUpdate(final PositionUpdate positionUpdate) {
        runOnUiThread(() -> {
            if (positionUpdate == null) {
                resetPositionTable();
                return;
            }
            updatePositionTable(positionUpdate);
        });
    }

    @Override
    public void onPowerSourceStateUpdate(StatusUpdate powerSourceUpdate) {
        runOnUiThread(() -> updatePowerSourceState(powerSourceUpdate));
    }

    private void updatePowerSourceState(StatusUpdate powerSourceUpdate)
    {
        String batteryStatus = "Unknown";
        if (powerSourceUpdate != null && powerSourceUpdate.getStatus() != null) {
            batteryStatus = powerSourceUpdate.getStatus();
        }
        binding.txtExternalReceiverBatteryLevelValue.setText(batteryStatus);
    }

    @Override
    public void onSatelliteSummaryUpdate(final MainModel.SatelliteSummaryUpdate summary) {
        runOnUiThread(() -> updateSatelliteSummary(summary));
    }

    private void updateSatelliteSummary(MainModel.SatelliteSummaryUpdate summary) {
        updateSatSummary(summary, R.id.txtGpsSummary, "G");

        updateSatSummary(summary, R.id.txtGlonassSummary, "R");

        updateSatSummary(summary, R.id.txtBeidouSummary, "B");

        updateSatSummary(summary, R.id.txtGalileoSummary, "E");

        updateSatSummary(summary, R.id.txtSbasSummary, "S");

        updateSatSummary(summary, R.id.txtQzssSummary, "Q");

        updateSatSummary(summary, R.id.txtIrnssSummary, "I");
    }

    private void updateSatSummary(MainModel.SatelliteSummaryUpdate summary, int txtSummary, String satType) {
        if (summary != null && summary.getSatelliteSummaryHashtable().containsKey(satType)) {
            Hashtable<String, MainModel.SatelliteSummary> satelliteSummaryHashtable = summary.getSatelliteSummaryHashtable();
            MainModel.SatelliteSummary satSummary = satelliteSummaryHashtable.get(satType);
            ((TextView) findViewById(txtSummary)).setText(String.format(Locale.getDefault(),"%d/%d", satSummary != null ? satSummary.used : 0, satSummary != null ? satSummary.tracked : 0));
        } else {
            ((TextView) findViewById(txtSummary)).setText("0/0");
        }
    }

    private void resetPositionTable() {
        binding.txtSolutionType.setText("");
        binding.txtLatitude.setText("");
        binding.txtLongitude.setText("");
        binding.txtHeight.setText("");
        binding.txtGroundPositionType.setText("");
        binding.txtHPrecision.setText("");
        binding.txtVPrecision.setText("");
        binding.txtSigmaSemiMajorAxis.setText("");
        binding.txtSigmaSemiMinorAxis.setText("");
        binding.txtSigmaOrientation.setText("");
        binding.txtImuState.setText("");
        binding.txtPitch.setText("");
        binding.txtRoll.setText("");
        binding.txtYaw.setText("");
        binding.txtPitchPrecision.setText("");
        binding.txtRollPrecision.setText("");
        binding.txtYawPrecision.setText("");
        binding.txtNoOfSatellites.setText("");
        binding.txtStaticEpoch.setText("");
        binding.txtCorrectionAge.setText("");
        binding.txtReceivedCorrectionData.setText("");
        binding.txtStationID.setText("");
        binding.txtIsTransformed.setText("");
        binding.txtSourceReferenceFrameID.setText("");
        binding.txtSourceEpoch.setText("");
        binding.txtReferenceFrameID.setText("");
        binding.txtEpoch.setText("");
        binding.txtElevation.setText("");
        binding.txtGeoidModel.setText("");
        binding.txtGpsTime.setText("");
        binding.txtUtcTime.setText("");
        binding.txtExternalReceiverBatteryLevelValue.setText("Unknown");
    }
    
    private String formatRadsToDegrees(double rad) {
        if(Double.isNaN(rad))
            return "NaN";

        double deg = rad * 180 / Math.PI;
        double min = abs(deg - (int) deg) * 60;
        double sec = abs(min - (int) min) * 60;

        return String.format(Locale.getDefault(),"%d°%d'%.4f\"",(int)deg,(int)min,sec);
    }


    private void updatePositionTable(final PositionUpdate positionUpdate) {
        binding.txtSolutionType.setText(positionUpdate.getSolution().toString());
        binding.txtLatitude.setText(formatRadsToDegrees(positionUpdate.getLatitude()));
        binding.txtLongitude.setText(formatRadsToDegrees(positionUpdate.getLongitude()));
        binding.txtHeight.setText(Units.Distance.format(positionUpdate.getHeight()));
        binding.txtGroundPositionType.setText(positionUpdate.getGroundPositionType().toString());
        binding.txtHPrecision.setText(Units.StandardDeviation.format(positionUpdate.getHPrecision()));
        binding.txtVPrecision.setText(Units.StandardDeviation.format(positionUpdate.getVPrecision()));
        binding.txtSigmaSemiMajorAxis.setText(Units.Distance.format(positionUpdate.getSigmaSemiMajorAxis()));
        binding.txtSigmaSemiMinorAxis.setText(Units.Distance.format(positionUpdate.getSigmaSemiMinorAxis()));
        binding.txtSigmaOrientation.setText(formatRadsToDegrees(positionUpdate.getSigmaOrientation()));
        binding.txtImuState.setText(positionUpdate.getInertialMeasurementUnitState().toString());
        binding.txtPitch.setText(formatRadsToDegrees(positionUpdate.getPitch()));
        binding.txtRoll.setText(formatRadsToDegrees(positionUpdate.getRoll()));
        binding.txtYaw.setText(formatRadsToDegrees(positionUpdate.getYaw()));
        binding.txtPitchPrecision.setText(formatRadsToDegrees(positionUpdate.getPitchPrecision()));
        binding.txtRollPrecision.setText(formatRadsToDegrees(positionUpdate.getRollPrecision()));
        binding.txtYawPrecision.setText(formatRadsToDegrees(positionUpdate.getYawPrecision()));
        binding.txtNoOfSatellites.setText(Integer.toString(positionUpdate.getNumberSatellites()) + " / " + Integer.toString(positionUpdate.getNumberTrackedSatellites()));
        binding.txtStaticEpoch.setText(Integer.toString(positionUpdate.getStaticEpochs()));
        binding.txtCorrectionAge.setText(String.format(Locale.getDefault(),"%.02f", positionUpdate.getCorrectionAge()));
        binding.txtReceivedCorrectionData.setText(positionUpdate.getReceivedCorrectionData() + " bytes");
        binding.txtStationID.setText(Integer.toString(positionUpdate.getStationId()));
        binding.txtIsTransformed.setText(positionUpdate.getDatumTransformationApplied() ? "Yes" : "No");
        binding.txtSourceReferenceFrameID.setText(MainModel.getReferenceFrameName(positionUpdate.getSourceReferenceFrame()));
        binding.txtSourceEpoch.setText(Double.toString(MainModel.getReferenceFrameEpoch(positionUpdate.getSourceReferenceFrame())));
        binding.txtReferenceFrameID.setText(MainModel.getReferenceFrameName(positionUpdate.getReferenceFrame()));
        binding.txtEpoch.setText(Double.toString(MainModel.getReferenceFrameEpoch(positionUpdate.getReferenceFrame())));
        binding.txtElevation.setText(Units.Distance.format(positionUpdate.getElevation()));
        binding.txtGeoidModel.setText(positionUpdate.getGeoidModel());
        binding.txtGpsTime.setText(positionUpdate.getGpsTime().toString());
        binding.txtUtcTime.setText(positionUpdate.getUtcTime().toString());
    }



    @Override
    public void onRtkConnectionStatusUpdate(final StatusUpdate statusUpdate) {
        runOnUiThread(() -> {
            String status = "";
            if (statusUpdate != null && statusUpdate.getStatus() != null) {
                status = statusUpdate.getStatus();
            }
            binding.txtRtkConnectionStatusValue.setText(status);
        });

    }

    @Override
    public void onSurveyTypeUpdate(final StatusUpdate statusUpdate) {
        runOnUiThread(() -> {
            String surveyType = "";
            if (statusUpdate != null && statusUpdate.getStatus() != null) {
                surveyType = statusUpdate.getStatus();
            }
            binding.txtRtkSurveyTypeValue.setText(surveyType);
        });
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

    protected void showInputPointNameDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Point Name:");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("OK", (dialog, which) -> {
            MainModel.getInstance().marksPoints(input.getText().toString());
            binding.txtMarker
                    .setText(String.format("PointName: %s", MainModel.getInstance().getPointName()));
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    protected void showProgress(String msg) {
        if (progressDialog != null)
            progressDialog.dismiss();
        progressDialog = ProgressBarFactory.createProgressDialog(this,msg);
        progressDialog.show();
    }

    protected void hideProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private String lastLoadedFileData;
    private Object loadUserSelectedFileGuard = new Object();
    private String loadUserSelectedFile(){
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_FILE);
        try {
            synchronized (loadUserSelectedFileGuard)
            {
                loadUserSelectedFileGuard.wait();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return lastLoadedFileData;
    }
}
