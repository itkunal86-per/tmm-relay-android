package trimble.jssi.android.catalystfacade.catalystfacadedemo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.EventObject;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import trimble.jssi.android.catalystfacade.CatalystFacade;
import trimble.jssi.android.catalystfacade.DriverReturnCode;
import trimble.jssi.android.catalystfacade.DriverType;
import trimble.jssi.android.catalystfacade.ICatalystEventListener;
import trimble.jssi.android.catalystfacade.ImuStateEvent;
import trimble.jssi.android.catalystfacade.PositionUpdate;
import trimble.jssi.android.catalystfacade.PowerSourceState;
import trimble.jssi.android.catalystfacade.ReturnCode;
import trimble.jssi.android.catalystfacade.ReturnObject;
import trimble.jssi.android.catalystfacade.RtkConnectionStatus;
import trimble.jssi.android.catalystfacade.SatelliteUpdate;
import trimble.jssi.android.catalystfacade.SensorProperties;
import trimble.jssi.android.catalystfacade.SensorStateEvent;
import trimble.jssi.android.catalystfacade.SubscriptionDetails;
import trimble.jssi.android.catalystfacade.TargetReferenceFrame;
import trimble.jssi.components.core.format.AngleType;
import trimble.jssi.components.core.format.UnitsAngle;
import trimble.jssi.connection.ConnectionState;
import trimble.jssi.interfaces.gnss.PositionRate;
import trimble.jssi.interfaces.gnss.positioning.IReferenceSystem;
import trimble.jssi.interfaces.gnss.satellites.ISatellite;
import trimble.jssi.interfaces.subscriptions.SubscriptionType;

public class MainModel implements ICatalystEventListener {


    private static final int BUFFER = 2048;




    static class StatusUpdate extends EventObject {
        /**
         * 
         */
        private static final long serialVersionUID = 2520083788179460062L;

        public StatusUpdate(Object source, ReturnCode returnCode, String status) {
            super(source);
            this.returnCode = returnCode;
            this.status = status;
        }

        public String getStatus() {
            return status;
        }

        public ReturnCode getReturnCode() {
            return returnCode;
        }

        private final ReturnCode returnCode;
        private final String status;
    }

    public static class SatelliteSummary {
        public char type;
        public int used;
        public int tracked;

        public SatelliteSummary(char type) {
            this.type = type;
            tracked = 0;
            used =0;
        }
    }

    static class SatelliteSummaryUpdate extends  EventObject{



        private final Hashtable<String,SatelliteSummary> satelliteSummaryHashtable;

        SatelliteSummaryUpdate(Object source,Hashtable<String, SatelliteSummary> satelliteSummaryHashtable) {
            super(source);
            this.satelliteSummaryHashtable = satelliteSummaryHashtable;
        }

        public Hashtable<String, SatelliteSummary> getSatelliteSummaryHashtable() {
            return satelliteSummaryHashtable;
        }
    }

    interface StatusUpdateListener {
        void onLicenseStatusUpdate(StatusUpdate statusUpdate);


        void onSensorStatusUpdate(StatusUpdate statusUpdate);


        void onPowerSourceStateUpdate(StatusUpdate powerSourceUpdate);

        void onSurveyStatusUpdate(StatusUpdate statusUpdate);
        
        void onRtkConnectionStatusUpdate(StatusUpdate statusUpdate);

        void onSurveyTypeUpdate(StatusUpdate statusUpdate);

        void onPositionUpdate(PositionUpdate positionUpdate);

        void onSatelliteSummaryUpdate(SatelliteSummaryUpdate summary);

        void onProgress(String msg);
    }


    private void triggerStatusUpdateListener(Action<StatusUpdateListener> action) {
        synchronized (statusUpdateListeners) {
            for (StatusUpdateListener statusUpdateListener : statusUpdateListeners) {
                try {
                    action.run(statusUpdateListener);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    enum SubscriptionTypes {
        User,
        Device,
        OffTheShelf
    }

    enum SurveyTypes {
        TrimbleCorrectionHub,
        RtxViaInternet,
        RtxViaSatellite,
        RtkViaNtrip
    }
    
    enum ConnectionTypes
    {
        Bluetooth,
        TcpIp
    }

    public static final String SubscriptionType = "SubscriptionType";
    public static final String DriverType = "DriverType";
    public static final String ConnectionType = "ConnectionType";
    public static final String DeviceAddress = "DeviceAddress";
    public static final String DeviceName = "DeviceName";
    public static final String DevicePortNo = "DevicePortNo";
    public static final String NtripServer = "NtripServer";
    public static final String NtripPort = "NtripPort";
    public static final String NtripUser = "NtripUser";
    public static final String NtripPassword = "NtripPassword";
    public static final String NtripSource = "NtripSource";
    public static final String SurveyType = "SurveyType";
    public static final String TargetReferenceFrame = "TargetReferenceFrame";
    public static final String TargetReferenceFrameId = "TargetReferenceFrameId";
    public static final String GeoidGridFileFullPath = "GeoidGridFileFullPath";
    public static final String ReducedAntennaHeight = "ReducedAntennaHeight";

    public static final String AppGuid = "5b25ac4a-0d41-4107-983c-77b6da298a8d";

    private File configFile;
    private File mountPointCacheFile;
    private File positionLogFile;
    private Context applicationContext;
    private CatalystFacade catalystFacade;
    private StatusUpdate licStatusUpdate;
    private StatusUpdate sensorStatusUpdate;
    private StatusUpdate surveyStatusUpdate;
    private PositionUpdate positionUpdate;
    private StatusUpdate rtkConnectionStatusUpdate;
    private StatusUpdate surveyTypeUpdate;
    private SatelliteSummaryUpdate satelliteSummaryUpdate;
    private StatusUpdate claimStatusUpdate;
    private StatusUpdate powerSourceUpdate;
    private boolean isGoStatic;
    private String progressMessage;
    private final Object progressLock = new Object();
    private final ArrayList<StatusUpdateListener> statusUpdateListeners = new ArrayList<>();
    private String pointName;
    private final LinkedBlockingQueue<String> positionUpdates = new LinkedBlockingQueue<>();
    private final Stack<String> progressMessages = new Stack<>();
    private final ExecutorService threadExecutor;
    private boolean licReady;
    private SubscriptionTypes usedSubscriptionType;
    static final UnitsAngle AngleUnits = new UnitsAngle(AngleType.Degree, 8);

    private static MainModel instance;

    public static MainModel getInstance() {
        if (instance == null) {
            instance = new MainModel();
        }
        return instance;
    }



    private MainModel() {
        threadExecutor = Executors.newSingleThreadExecutor();
        try {
            positionUpdates.put(
                    "LogTimeStamp,GpsTime,PointName,Solution,Lat,Lon,Height,GroundPositionType,HPrecision,VPrecision,SigmaSemiMajorAxis,SigmaSemiMinorAxis,SigmaOrientation," +
                            "InertialMeasurementUnitState,Pitch,Roll,Yaw,PitchPrecision,RollPrecision,YawPrecision,Satellites,SatellitesTracked,StaticEpochs,CorrectionAge," +
                            "CorrectionDataCount,IsTransformationApplied,ReferenceFrameID,Epoch,MSLHeight");
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        }
        Runnable runnable = () -> {
            String line = "";
            BufferedWriter bufferedWriter = null;
            try {
                do {
                    try {
                        line = positionUpdates.take();

                        if (bufferedWriter == null) {
                            bufferedWriter = new BufferedWriter(new FileWriter(getPositionLogFile(), true));
                        }
                        bufferedWriter.write(line);
                        bufferedWriter.newLine();
                        // flush after each line so that we have the most recent data on disk in case of a crash
                        bufferedWriter.flush();
                    } catch (InterruptedException | IOException e) {
                        e.printStackTrace();
                    }
                } while (!line.equals("***END*POSITION*UPDATES***"));
            } finally {
                if (bufferedWriter != null) {
                    try {
                        bufferedWriter.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                // Reset for next log
                isGoStatic = false;
            }
        };
        runOnThread(runnable,true);
    }


    public boolean isUsingTMM() {
        return usedSubscriptionType == SubscriptionTypes.User;
    }


    public boolean isDriverLogsEnabled() {
        return catalystFacade.isDriverLogsEnabled();
    }

    public void enableDriverLogs(boolean isChecked) {
        catalystFacade.enableDriverLogs(isChecked);
    }

    public int compressDriverLogs(ParcelFileDescriptor pfd) throws IOException {

        File directorySelected = new File(catalystFacade.getDriverLogFilePath());
        File[] files = directorySelected.listFiles();
        {
            if(files == null || files.length == 0) {
                throw new RuntimeException("No log files.");
            }
            FileOutputStream fileOutputStream = new FileOutputStream(pfd.getFileDescriptor());
            ZipOutputStream out = new ZipOutputStream(fileOutputStream);
            byte[] data = new byte[BUFFER];

            for (File F : files) {
                if (!F.exists()) continue;

                long length = F.length();

                FileInputStream fi = new FileInputStream(F);
                {
                    ZipEntry entry = new ZipEntry(F.getName());
                    out.putNextEntry(entry);
                    int count;
                    while ((count = fi.read(data, 0, BUFFER)) != -1) {
                        out.write(data, 0, count);
                        if ((length -= count) <= 0) break;
                    }
                    fi.close();
                }
            }
            out.close();
            fileOutputStream.close();
        }
        return files.length;
    }

    @Override
    protected void finalize() throws Throwable {
        positionUpdates.put("***END*POSITION*UPDATES***");
        super.finalize();
    }

    public StatusUpdate getLicStatusUpdate() {
        return licStatusUpdate;
    }

    public StatusUpdate getSensorStatusUpdate() {
        return sensorStatusUpdate;
    }

    public StatusUpdate getSurveyStatusUpdate() {
        return surveyStatusUpdate;
    }

    public void setSurveyStatusErrorMessage(String statusMessage) {
        surveyStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Error), statusMessage);
    }

    public PositionUpdate getPositionUpdate() {
        return positionUpdate;
    }

    public SatelliteSummaryUpdate getSatelliteSummaryUpdate() {
        return satelliteSummaryUpdate;
    }

    public StatusUpdate getRtkConnectionStatusUpdate() {
       return rtkConnectionStatusUpdate;
   }

    public StatusUpdate getSurveyTypeUpdate() { return surveyTypeUpdate; }

    public StatusUpdate getClaimStatusUpdate() { return claimStatusUpdate; }

    public StatusUpdate getPowerSourceUpdate() { return powerSourceUpdate; }

    public File getConfigFile() {
        if (configFile == null) {
            configFile = new File(applicationContext.getFilesDir().getAbsolutePath() + File.separator + "config.properties");
        }
        return configFile;
    }

    public File getMountPointCacheFile() {
        if (mountPointCacheFile == null) {
            mountPointCacheFile = new File(applicationContext.getFilesDir().getAbsolutePath() + File.separator + "mountPoints");
        }
        return mountPointCacheFile;
    }

    public File getPositionLogFile() {
        if (positionLogFile == null) {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMddHHmmss",Locale.getDefault());
            String filePath = applicationContext.getExternalFilesDir(null).getAbsolutePath() + File.separator
                    + "PositionLog" + File.separator + "position_log_" + simpleDateFormat.format(new Date()) + ".csv";
            positionLogFile = new File(filePath);
            positionLogFile.getParentFile().mkdirs();
        }
        return positionLogFile;
    }

    public String getProgressMessage() {
        synchronized (progressLock) {
            return progressMessage;
        }
    }

    public void setProgressMessage(final String progressMessage) {
        synchronized (progressLock) {
            if(progressMessage == null) {
                this.progressMessage = null;
                if(progressMessages.size() != 0) 
                {
                    this.progressMessage = progressMessages.pop();
                }
            }
            else {
                if(this.progressMessage != null) {
                    progressMessages.push(this.progressMessage);
                }
                this.progressMessage = progressMessage;
            }
            triggerStatusUpdateListener(new Action<StatusUpdateListener>() {

                @Override
                public void run(StatusUpdateListener item) {
                    item.onProgress(MainModel.this.progressMessage);
                }
            });
        }
    }

    public void addStatusUpdateListener(StatusUpdateListener statusUpdateListener) {
        synchronized (statusUpdateListeners) {
            statusUpdateListeners.add(statusUpdateListener);
        }
    }

    public void removeStatusUpdateListener(StatusUpdateListener statusUpdateListener) {
        synchronized (statusUpdateListeners) {
            statusUpdateListeners.remove(statusUpdateListener);
        }
    }

    public void init(Context applicationContext) {
        this.applicationContext = applicationContext;
        usedSubscriptionType = parseSubscriptionType(readConfig());
        catalystFacade = new CatalystFacade(AppGuid, applicationContext);
    }

    private void  reInit()
    {
        if( catalystFacade != null && catalystFacade.isSensorConnected()) {
            disconnect();
            catalystFacade.releaseDriver();
        }
        usedSubscriptionType = parseSubscriptionType(readConfig());
        licReady = false;
        licStatusUpdate = null;
        sensorStatusUpdate = null;
        triggerStatusUpdateListener(new Action<StatusUpdateListener>() {

            @Override
            public void run(StatusUpdateListener item) {
                item.onLicenseStatusUpdate(licStatusUpdate);
                item.onSensorStatusUpdate(sensorStatusUpdate);
            }
        });
    }

    public String getConfiguredReceiverName() {
        Properties config = readConfig();
        return catalystFacade.getReceiverName(getDriverType(config.getProperty(DriverType)), config.getProperty(DeviceName));
    }



    public void beginLoadSubscription(final String userTID) {
        Runnable runnable = () -> loadSubscription(userTID);
        runOnThread(runnable,false);
    }

    public void setCurrentClaim(String currentClaim) {
        if(currentClaim != null) {
            claimStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Success), "Claim:" + currentClaim);
            triggerStatusUpdateListener(new Action<StatusUpdateListener>() {

                @Override
                public void run(StatusUpdateListener item) {
                    item.onLicenseStatusUpdate(claimStatusUpdate);
                }
            });
        } else {
            claimStatusUpdate = null;
            if (licStatusUpdate != null) {
                triggerStatusUpdateListener(new Action<StatusUpdateListener>() {

                    @Override
                    public void run(StatusUpdateListener item) {
                        item.onLicenseStatusUpdate(licStatusUpdate);
                    }
                });
            }
        }

    }


    public void loadSubscription(String userTID) {
        if(userTID == null)
        {
            licStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Error), "Loading Subscription Failed");
            triggerStatusUpdateListener(new Action<StatusUpdateListener>() {

                @Override
                public void run(StatusUpdateListener item) {
                    item.onLicenseStatusUpdate(licStatusUpdate);
                }
            });
            return;
        }
        setProgressMessage("Loading Subscription");
        ReturnCode retCode;
        String licenseInformation = "";
        if(usedSubscriptionType == SubscriptionTypes.User) {
            retCode = catalystFacade.loadSubscriptionFromTrimbleMobileManager(userTID);
         } else if (usedSubscriptionType == SubscriptionTypes.Device) {
            String deviceLicense = LoadUserSelectedFile();
            if(deviceLicense.isEmpty())
                retCode = new ReturnCode(DriverReturnCode.ErrorCancelledFromUser);
            else {
                ReturnObject<SubscriptionDetails> subscription = catalystFacade.loadDeviceSubscription(deviceLicense);
                retCode = new ReturnCode(subscription.getCode());

                licenseInformation = String.format("\n%s:issue %s:expiry %s", subscription.getReturnedObject().getSubscriptionName(),
                        subscription.getReturnedObject().getIssueDate(), subscription.getReturnedObject().getExpiryDate());
            }
        } else {
            retCode = catalystFacade.loadSubscription();
        }

        licStatusUpdate = new StatusUpdate(this, retCode,
                retCode.getCode() == DriverReturnCode.Success ? "Subscription Loaded" + licenseInformation : "Loading Subscription Failed");
        triggerStatusUpdateListener(new Action<StatusUpdateListener>() {

            @Override
            public void run(StatusUpdateListener item) {
                item.onLicenseStatusUpdate(licStatusUpdate);
            }
        });

        if (retCode.getCode() == DriverReturnCode.Success) {
            licReady = true;
        	Properties config = readConfig();
            String deviceTypeStr;
            if (null == config || null == (deviceTypeStr = config.getProperty(DriverType)))
            {
            	sensorStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Error), "Unknown device type");
            	triggerStatusUpdateListener(new Action<StatusUpdateListener>() {
                    @Override
                    public void run(StatusUpdateListener item) {
                        item.onSensorStatusUpdate(sensorStatusUpdate);
                    }
                });
                return;
            }

            trimble.jssi.android.catalystfacade.DriverType driverType = getDriverType(deviceTypeStr);
            final ReturnCode retCodeInitDriver = catalystFacade.initDriver(driverType);
        	sensorStatusUpdate = new StatusUpdate(this, retCodeInitDriver,
                    retCodeInitDriver.getCode() == DriverReturnCode.Success ? deviceTypeStr + " driver loaded" : "Unable to load " + deviceTypeStr + " driver");
            triggerStatusUpdateListener(new Action<StatusUpdateListener>() {

                @Override
                public void run(StatusUpdateListener item) {
                    item.onSensorStatusUpdate(sensorStatusUpdate);
                }
            });
        } else {
            licReady = false;
        }
        setProgressMessage(null);
    }

    private Func<String> userSelectFileAndLoadAction;
    public void ConfigureLoadUserSelectFileAction(Func<String> userSelectFileAndLoadAction)
    {
        this.userSelectFileAndLoadAction = userSelectFileAndLoadAction;
    }
    private String LoadUserSelectedFile()
    {
        return userSelectFileAndLoadAction.run();
    }

    private trimble.jssi.android.catalystfacade.DriverType getDriverType(String deviceTypeStr) {
        DriverType driverType;
        try {
            driverType = trimble.jssi.android.catalystfacade.DriverType.valueOf(deviceTypeStr);
        } catch (IllegalArgumentException ex) {
            driverType = trimble.jssi.android.catalystfacade.DriverType.TrimbleGNSS;//Default
        }
        return driverType;
    }

    public void beginConnect() {
        Runnable runnable = this::connect;
        runOnThread(runnable,false);
    }

    public void connect() {
        setProgressMessage("Connecting");

        ReturnCode retCode = new ReturnCode(DriverReturnCode.Error);
        Properties config = readConfig();
        String connectionType;
        DriverType driverType = readDriverTypeFromConfig();

        if (null == config) {
            sensorStatusUpdate = new StatusUpdate(this, retCode, "Unable to connect");
        } else {
            if (driverType == trimble.jssi.android.catalystfacade.DriverType.Catalyst ||
                    driverType == trimble.jssi.android.catalystfacade.DriverType.EM100 ||
                    driverType == trimble.jssi.android.catalystfacade.DriverType.TDC150  ) {
                retCode = catalystFacade.connect();
            } 
            else if (driverType == trimble.jssi.android.catalystfacade.DriverType.TrimbleGNSS ||
                    driverType == trimble.jssi.android.catalystfacade.DriverType.SpectraPrecision) {
                if (null == (connectionType = config.getProperty(ConnectionType))) {
                    sensorStatusUpdate = new StatusUpdate(this, retCode, "Unable to connect");
                } 
                else {

                    ConnectionTypes connType = ConnectionTypes.valueOf(connectionType);
                    switch (connType) {
                    case Bluetooth:
                        retCode = catalystFacade.connectViaBluetooth(config.getProperty(DeviceAddress));
                        break;
                    case TcpIp:
                        retCode = catalystFacade.connectViaWifi(config.getProperty(DeviceAddress), config.getProperty(DevicePortNo));
                        break;
                    }
                }
            } else if (driverType == trimble.jssi.android.catalystfacade.DriverType.Mock) {
                retCode = catalystFacade.connectMock();
            }

            if (retCode.getCode() == DriverReturnCode.Success) {
                ReturnObject<SensorProperties> sensorProperites = catalystFacade.getSensorProperties();
                if(sensorProperites.getReturnedObject().isLicensed()) {
                    sensorStatusUpdate = new StatusUpdate(this, retCode,
                            String.format("Connected to %s:%s:FW-%s", sensorProperites.getReturnedObject().getInstrumentName(),
                                    sensorProperites.getReturnedObject().getSerialNumber(), sensorProperites.getReturnedObject().getFirmware()));
                    catalystFacade.addCatalystEventListener(this);
                } else {
                    catalystFacade.disconnectFromSensor();
                    sensorStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.ErrorNoLicense), "The instrument is not licensed");
                    triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {
                        @Override
                        public void run(StatusUpdateListener item) {
                            item.onSensorStatusUpdate(sensorStatusUpdate);
                        }
                    });
                    setProgressMessage(null);
                    return;
                }
            } 
            else {
                sensorStatusUpdate = new StatusUpdate(this, retCode, "Unable to connect");
                setProgressMessage(null);
                return;
            }
        }

        if (retCode.getCode() == DriverReturnCode.Success)
        {
            setReducedAntennaHeight();
        }

        //Setting position rate. Default value even without calling this function is 1 HZ
        ReturnCode returnCode = catalystFacade.setOutputPositionRate(PositionRate.OneHz);
        if(returnCode.getCode() != DriverReturnCode.Success) {
            sensorStatusUpdate = new StatusUpdate(this, returnCode, "Unable to set output position rate");
        }

        triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {
            @Override
            public void run(StatusUpdateListener item) {
                item.onSensorStatusUpdate(sensorStatusUpdate);
            }
        });


        setProgressMessage(null);
    }

    private void setReducedAntennaHeight() {
        try {
            Properties config = readConfig();
            String reducedAntennaHeightStr;
            if (config != null && (reducedAntennaHeightStr = config.getProperty(ReducedAntennaHeight)) != null) {
                double reducedAntennaHeight = 0.0;

                if (!reducedAntennaHeightStr.isEmpty()) {
                    reducedAntennaHeight = Double.parseDouble(reducedAntennaHeightStr);
                }
                catalystFacade.setReducedAntennaHeight(reducedAntennaHeight);
            }
        } catch (NumberFormatException e) {
            //Ignoring exceptions
        }
    }

    public Boolean isCatalystDA1Selected()
    {
        DriverType driverType = readDriverTypeFromConfig();

        return (driverType == trimble.jssi.android.catalystfacade.DriverType.Catalyst);
    }

    private SubscriptionTypes readSubscriptionTypeFromConfig()
    {
        Properties config = readConfig();

        return parseSubscriptionType(config);
    }

    private SubscriptionTypes parseSubscriptionType(Properties config)
    {
        String subscriptionTypeStr = config.getProperty(SubscriptionType);
        if (subscriptionTypeStr == null)
        {
            return null;
        }
        try {
            return SubscriptionTypes.valueOf(subscriptionTypeStr);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private DriverType readDriverTypeFromConfig()
    {
        Properties config = readConfig();

        return parseDriverType(config);
    }

    private DriverType parseDriverType(Properties config)
    {
        String deviceTypeStr = config.getProperty(DriverType);
        if (deviceTypeStr == null)
        {
            return null;
        }
        try {
            return trimble.jssi.android.catalystfacade.DriverType.valueOf(deviceTypeStr);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public void beginDisonnect() {
        Runnable runnable = this::disconnect;
        runOnThread(runnable,false);
    }

    public void disconnect() {
        setProgressMessage("Disconnecting");
        catalystFacade.removeCatalystEventListener(this);
        ReturnCode retCode = catalystFacade.disconnectFromSensor();
        sensorStatusUpdate =
                new StatusUpdate(this, retCode, retCode.getCode() == DriverReturnCode.Success ? "Disconnected" : "Unable to disconnect");
        if(retCode.getCode() == DriverReturnCode.Success) {
            positionUpdate = null;
            satelliteSummaryUpdate = null;
            surveyStatusUpdate = null;
            rtkConnectionStatusUpdate  = null;
            surveyTypeUpdate = null;
        }
        triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

            @Override
            public void run(StatusUpdateListener item) {
                item.onSensorStatusUpdate(sensorStatusUpdate);
                item.onPositionUpdate(positionUpdate);
                item.onSatelliteSummaryUpdate(satelliteSummaryUpdate);
                item.onSurveyStatusUpdate(surveyStatusUpdate);
                item.onRtkConnectionStatusUpdate(rtkConnectionStatusUpdate);
                item.onSurveyTypeUpdate(surveyTypeUpdate);
            }
        });
        setProgressMessage(null);
    }

    public void beginGetNtripSource() {
        Runnable runnable = this::getNtripSource;
        runOnThread(runnable,false);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void getNtripSource() {

        Properties config = readConfig();
        if(!isValidNtripServerConfig(config))
        {
            surveyStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Error), "Unable to fetch Ntrip Source (configuration error)");
            triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

                @Override
                public void run(StatusUpdateListener item) {
                    item.onSurveyStatusUpdate(surveyStatusUpdate);
                }
            });
            return;
        }
        setProgressMessage("Getting Ntrip Source list");
        ReturnObject<Collection<String>> returnObject =
                catalystFacade.getNtripSourceTable(config.getProperty(NtripServer), Integer.parseInt(config.getProperty(NtripPort)), config.getProperty(NtripUser), config.getProperty(NtripPassword));
        if (returnObject.getCode() == DriverReturnCode.Success) {
            surveyStatusUpdate = new StatusUpdate(this, returnObject, "Fetched Ntrip Source");
            if (getMountPointCacheFile().exists()) {
                getMountPointCacheFile().delete();
            }
            BufferedWriter bufferedWriter = null;
            try {
                bufferedWriter = new BufferedWriter(new FileWriter(getMountPointCacheFile()));
                for (String mountPoint : returnObject.getReturnedObject()) {
                    bufferedWriter.write(mountPoint);
                    bufferedWriter.newLine();
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    if (bufferedWriter != null) {
                        bufferedWriter.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        } else {
            surveyStatusUpdate = new StatusUpdate(this, returnObject, "Unable to fetch Ntrip Source");
        }
        triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

            @Override
            public void run(StatusUpdateListener item) {
                item.onSurveyStatusUpdate(surveyStatusUpdate);
            }

        });
        setProgressMessage(null);
    }

    public void beginStartSurvey() {
        Runnable runnable = this::startSurvey;
        runOnThread(runnable,false);
    }

    public void startSurvey() {
        setProgressMessage("Starting Survey");
        SurveyTypes surveyType = SurveyTypes.TrimbleCorrectionHub;
        TargetReferenceFrame targetReferenceFrame = trimble.jssi.android.catalystfacade.TargetReferenceFrame.ToLocal;
        int targetReferenceFrameId = 0;
        String geoidGridFileFullPath = "";
        Properties config = readConfig();
        if (config != null) {
            surveyType = SurveyTypes.valueOf(SurveyTypes.class, config.getProperty(SurveyType));
            String targetReferenceFrameStr = config.getProperty(TargetReferenceFrame);
            targetReferenceFrame = trimble.jssi.android.catalystfacade.TargetReferenceFrame.valueOf(targetReferenceFrameStr);
            if (!config.getProperty(TargetReferenceFrameId).isEmpty())
                targetReferenceFrameId = Integer.parseInt(config.getProperty(TargetReferenceFrameId));
            geoidGridFileFullPath = config.getProperty(GeoidGridFileFullPath);
        }
        ReturnCode returnCode = new ReturnCode(DriverReturnCode.Error);

        switch (surveyType) {
        case TrimbleCorrectionHub:
            if (isUsingTMM())
                returnCode = catalystFacade.startTrimbleCorrectionHubSurvey(trimble.jssi.android.catalystfacade.TargetReferenceFrame.UseLocalSettings);
            else
                returnCode = catalystFacade.startTrimbleCorrectionHubSurvey(targetReferenceFrame, targetReferenceFrameId, geoidGridFileFullPath);
            break;
        case RtxViaInternet:
            if (isUsingTMM())
                returnCode = catalystFacade.startRtxViaInternet(trimble.jssi.android.catalystfacade.TargetReferenceFrame.UseLocalSettings);
            else
                returnCode = catalystFacade.startRtxViaInternet(targetReferenceFrame, targetReferenceFrameId, geoidGridFileFullPath);
            break;
        case RtxViaSatellite:
            if (isUsingTMM())
                returnCode = catalystFacade.startRtxViaSatellite(trimble.jssi.android.catalystfacade.TargetReferenceFrame.UseLocalSettings);
            else
                returnCode = catalystFacade.startRtxViaSatellite(targetReferenceFrame, targetReferenceFrameId, geoidGridFileFullPath);
            break;
        case RtkViaNtrip:
            if (config != null) {
                if(!isValidNtripServerConfig(config) || !isNtripUserConfig(config) || !isMountPointConfig(config))
                {
                    surveyStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Error), "Unable to Start Survey (configuration error)");
                } else {
                    String sourceEntry = config.getProperty(NtripSource);
                    String mountPoint = sourceEntry;
                    String[] sourceArray = sourceEntry.split(";", 3);
                    if (sourceArray.length > 0) {
                        mountPoint = sourceArray[0];
                    }

                    if (isUsingTMM())
                        returnCode = catalystFacade.startRTKViaNtrip(config.getProperty(NtripServer), Integer.parseInt(config.getProperty(NtripPort)),
                                config.getProperty(NtripUser), config.getProperty(NtripPassword), mountPoint, trimble.jssi.android.catalystfacade.TargetReferenceFrame.UseLocalSettings);
                    else
                        returnCode = catalystFacade.startRTKViaNtrip(config.getProperty(NtripServer), Integer.parseInt(config.getProperty(NtripPort)),
                                config.getProperty(NtripUser), config.getProperty(NtripPassword), mountPoint, targetReferenceFrame, geoidGridFileFullPath);
                }
            } else {
                surveyStatusUpdate = new StatusUpdate(this, returnCode, "Unable to Start Survey (no configuration)");
                triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

                    @Override
                    public void run(StatusUpdateListener item) {
                        item.onSurveyStatusUpdate(surveyStatusUpdate);
                    }
                });
                setProgressMessage(null);
                return;
            }
            break;
        default:
            surveyStatusUpdate = new StatusUpdate(this, returnCode, "Unable to Start Survey (unknown survey type)");
            triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

                @Override
                public void run(StatusUpdateListener item) {
                    item.onSurveyStatusUpdate(surveyStatusUpdate);
                }
            });
            setProgressMessage(null);
            return;
        }
        surveyStatusUpdate =
                new StatusUpdate(this, returnCode, returnCode.getCode() == DriverReturnCode.Success ? "Survey Started" : "Unable to Start Survey");
        triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

            @Override
            public void run(StatusUpdateListener item) {
                item.onSurveyStatusUpdate(surveyStatusUpdate);
            }
        });
        setProgressMessage(null);
    }

    public void beginEndSurvey() {
        Runnable runnable = this::endSurvey;
        runOnThread(runnable,false);
    }

    public void endSurvey() {
        setProgressMessage("Ending Survey");
        ReturnCode retCode = catalystFacade.endSurvey();
        surveyStatusUpdate =
                new StatusUpdate(this, retCode, retCode.getCode() == DriverReturnCode.Success ? "Survey Ended" : "Unable to End Survey");
        rtkConnectionStatusUpdate = null;
        triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

            @Override
            public void run(StatusUpdateListener item) {
                item.onSurveyStatusUpdate(surveyStatusUpdate);
                item.onRtkConnectionStatusUpdate(rtkConnectionStatusUpdate);
            }
        });
        
        setProgressMessage(null);
    }

    public void beginReadConfig(final Action<Properties> configReader) {
        Runnable runnable = () -> configReader.run(readConfig());
        runOnThread(runnable,false);
    }

    public Properties readConfig() {
        File configFile = getConfigFile();
        if (!configFile.exists()) {
            createDefaultConfig();
        }
        if (!configFile.exists()) {
            return null;
        }
        setProgressMessage("Reading Configuration");
        Properties properties = new Properties();
        FileInputStream fileInputStream = null;
        try {
            fileInputStream = new FileInputStream(configFile);
            properties.load(fileInputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (fileInputStream != null) {
            try {
                fileInputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        setProgressMessage(null);
        return properties;
    }

    public void createDefaultConfig() {
        Properties properties = new Properties();
        properties.setProperty(SubscriptionType, SubscriptionTypes.User.name());
        properties.setProperty(DriverType, trimble.jssi.android.catalystfacade.DriverType.TrimbleGNSS.name());
        properties.setProperty(ConnectionType, ConnectionTypes.Bluetooth.toString());
        properties.setProperty(DeviceAddress, "");
        properties.setProperty(DeviceName, "");
        properties.setProperty(DevicePortNo, "");
        properties.setProperty(NtripServer, "");
        properties.setProperty(NtripPort, "");
        properties.setProperty(NtripUser, "");
        properties.setProperty(NtripPassword, "");
        properties.setProperty(NtripSource, "");
        properties.setProperty(TargetReferenceFrame, trimble.jssi.android.catalystfacade.TargetReferenceFrame.Off.toString());
        properties.setProperty(TargetReferenceFrameId, "-1");
        properties.setProperty(GeoidGridFileFullPath, "");
        properties.setProperty(SurveyType, SurveyTypes.TrimbleCorrectionHub.toString());
        writeConfigToFile(properties);
    }

    public void beginReadMountPointCache(final Action<List<String>> mountPointCacheReader) {
        Runnable runnable = () -> mountPointCacheReader.run(readMountPointCache());
        runOnThread(runnable,false);
    }

    List<String> readMountPointCache() {
        List<String> mountPoints = new ArrayList<>();
        File mountPointCache = getMountPointCacheFile();
        if (!mountPointCache.exists()) {
            return mountPoints;
        }
        setProgressMessage("Reading NTRIP Source list cache");
        BufferedReader bufferedReader = null;
        try {
            bufferedReader = new BufferedReader(new FileReader(getMountPointCacheFile()));
            String line;
            do {
                line = bufferedReader.readLine();
                if (line != null) {
                    mountPoints.add(line);
                }
            } while (line != null);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
        setProgressMessage(null);
        return mountPoints;
    }

    public void beginWriteConfig(final Properties properties) {
        Runnable runnable = () -> writeConfig(properties);
        runOnThread(runnable,false);
    }

    public void writeConfig(Properties properties) {
        setProgressMessage("Saving Configuration");

        SubscriptionTypes oldSubscriptionType = readSubscriptionTypeFromConfig();
        DriverType oldDriverType = readDriverTypeFromConfig();

        writeConfigToFile(properties);

        String subscriptionTypeStr = properties.getProperty(SubscriptionType);
        SubscriptionTypes newSubscriptionType = SubscriptionTypes.valueOf(subscriptionTypeStr);
        if (oldSubscriptionType != newSubscriptionType)
        {
            reInit();
        }

        if (licReady) {
            String deviceTypeStr = properties.getProperty(DriverType);
            DriverType newDriverType = trimble.jssi.android.catalystfacade.DriverType.valueOf(deviceTypeStr);
            if (newDriverType != oldDriverType) {
                disconnect();
                catalystFacade.releaseDriver();
                final ReturnCode retCodeInitDriver = catalystFacade.initDriver(newDriverType);
                sensorStatusUpdate = new StatusUpdate(this, retCodeInitDriver, retCodeInitDriver.getCode() == DriverReturnCode.Success
                        ? deviceTypeStr + " driver loaded" : "Unable to load " + deviceTypeStr + " driver");
                triggerStatusUpdateListener(new Action<StatusUpdateListener>() {

                    @Override
                    public void run(StatusUpdateListener item) {
                        item.onSensorStatusUpdate(sensorStatusUpdate);
                    }
                });
            }
        }
        setProgressMessage(null);
    }

    public void writeConfigToFile(Properties properties) {
        if (getConfigFile().exists()) {
            getConfigFile().delete();
        }

        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(getConfigFile());
            properties.store(fileOutputStream, "Configuration CatalystFacade");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        setReducedAntennaHeight();
    }

    public void beginDeleteConfig() {
        Runnable runnable = this::deletConfig;
        runOnThread(runnable,false);
    }

    public void deletConfig() {
        setProgressMessage("Deleting Configuration");
        if (getConfigFile().exists()) {
            getConfigFile().delete();
        }
        if (getMountPointCacheFile().exists()) {
            getMountPointCacheFile().delete();
        }
        setProgressMessage(null);
    }

    @Override
    public void onSensorStateChanged(SensorStateEvent sensorStateEvent) {
        if(sensorStateEvent.getSensorState() == ConnectionState.LinkDown) 
        {
            sensorStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.ErrorNotConnected), "Link Down");
            positionUpdate = null;
            satelliteSummaryUpdate = null;
            triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

                @Override
                public void run(StatusUpdateListener item) {
                    item.onSensorStatusUpdate(sensorStatusUpdate);
                    item.onPositionUpdate(positionUpdate);
                    item.onSatelliteSummaryUpdate(satelliteSummaryUpdate);
                }
            });
        }
        try {
            positionUpdates.put(createPositionLog("Sensor:" + sensorStateEvent.getSensorState().toString()));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onUsbConnectionErrorOccured() {
        sensorStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Error), "Usb ConnectionError Occured");
        triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

            @Override
            public void run(StatusUpdateListener item) {
                item.onSensorStatusUpdate(sensorStatusUpdate);
            }
        });
    }

    @Override
    public void onSubscriptionHasExpired() {
        licStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.ErrorNoLicense), "Subscritpion has expired");
        triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

            @Override
            public void run(StatusUpdateListener item) {
                item.onLicenseStatusUpdate(licStatusUpdate);
            }
        });
    }

    private static double radToDeg(double rad) {
        return rad * 180 / Math.PI;
    }

    @Override
    public void onPositionUpdate(final PositionUpdate positionUpdate) {
        try {

            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault());
            positionUpdates.put(createPositionLog(
                    String.format(Locale.ROOT, "%s,%s,%s,%.8f,%.8f,%.4f,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%s,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%d,%d,%d,%f,%d,%s,%s,%.2f,%.4f",
                            simpleDateFormat.format(positionUpdate.getGpsTime()), pointName != null ? pointName : "Roving", positionUpdate.getSolution().toString(),
                            radToDeg(positionUpdate.getLatitude()), radToDeg(positionUpdate.getLongitude()), positionUpdate.getHeight(), positionUpdate.getGroundPositionType().toString(),
                            positionUpdate.getHPrecision(), positionUpdate.getVPrecision(),
                            positionUpdate.getSigmaSemiMajorAxis(), positionUpdate.getSigmaSemiMinorAxis(), radToDeg(positionUpdate.getSigmaOrientation()),
                            positionUpdate.getInertialMeasurementUnitState().toString(), radToDeg (positionUpdate.getPitch()), radToDeg(positionUpdate.getRoll()), radToDeg(positionUpdate.getYaw()),
                            radToDeg (positionUpdate.getPitchPrecision()), radToDeg(positionUpdate.getRollPrecision()), radToDeg(positionUpdate.getYawPrecision()),
                            positionUpdate.getNumberSatellites(), positionUpdate.getNumberTrackedSatellites(),positionUpdate.getStaticEpochs(), positionUpdate.getCorrectionAge(),
                            positionUpdate.getReceivedCorrectionData(), (positionUpdate.getDatumTransformationApplied() ? "Yes" : "No"),
                            getReferenceFrameName(positionUpdate.getReferenceFrame()), getReferenceFrameEpoch(positionUpdate.getReferenceFrame()),
                            positionUpdate.getElevation())));
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.positionUpdate = positionUpdate;
        triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

            @Override
            public void run(StatusUpdateListener item) {
                item.onPositionUpdate(positionUpdate);
            }
        });

    }

    static String getReferenceFrameName(IReferenceSystem referenceSystem) {
        return referenceSystem == null ? "Off" : referenceSystem.getName();
    }

    static double getReferenceFrameEpoch(IReferenceSystem referenceSystem)
    {
        double ret = referenceSystem != null?referenceSystem.getEpoch():Double.NaN;
        ret = ((double) Math.round(ret * 10)) / 10;
        return ret;
    }

    @Override
    public void onSatelliteUpdate(SatelliteUpdate satelliteUpdate, int satellitesInView) {
        List<ISatellite> satellites = satelliteUpdate.getSatellites();
        final Hashtable<String,SatelliteSummary> summary = new Hashtable<>();

        for (ISatellite satellite: satellites) {
           String key = String.format("%c",satellite.getSatelliteTypeChar());
           if (!summary.containsKey(key)) {
               summary.put(key, new SatelliteSummary(satellite.getSatelliteTypeChar()));
           }
           if (satellite.getEnabled()) {
               summary.get(key).tracked++;
           }
           if (satellite.getUsed()) {
               summary.get(key).used++;
           }
        }
        satelliteSummaryUpdate = new SatelliteSummaryUpdate(this, summary);

        triggerStatusUpdateListener(new Action<StatusUpdateListener>() {
            @Override
            public void run(StatusUpdateListener item) {
                item.onSatelliteSummaryUpdate(satelliteSummaryUpdate);
            }
        });
    }

    
	@Override
	public void onRtkServiceAvailable() {
       surveyStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Success), "Rtk service is available");
        triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

            @Override
            public void run(StatusUpdateListener item) {
                item.onSurveyStatusUpdate(surveyStatusUpdate);
            }
        });
		
	}

	@Override
	public void onRtxServiceAvailable() {
       surveyStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Success), "Rtx service is available");
        triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

            @Override
            public void run(StatusUpdateListener item) {
                item.onSurveyStatusUpdate(surveyStatusUpdate);
            }
        });
	}
	
	@Override
    public void onRtkConnectionStatusUpdate(RtkConnectionStatus rtkConnectionStatus) {
        rtkConnectionStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Success), rtkConnectionStatus.toString());
        triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {
            
            @Override
            public void run(StatusUpdateListener item) {
                item.onRtkConnectionStatusUpdate(rtkConnectionStatusUpdate);
            }
        });
    }

    @Override
    public void onSurveyTypeUpdate(trimble.jssi.android.catalystfacade.SurveyType surveyType) {
        surveyTypeUpdate  = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Success), surveyType.toString());
        triggerStatusUpdateListener(new Action<StatusUpdateListener>() {
            @Override
            public void run(StatusUpdateListener item) {
                item.onSurveyTypeUpdate(surveyTypeUpdate);
            }
        });
    }

    @Override
    public void onSensorOutsideGeofence() {
        surveyStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Error), "Sensor is outside geofence");
        triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

            @Override
            public void run(StatusUpdateListener item) {
                item.onSurveyStatusUpdate(surveyStatusUpdate);
            }
        });
    }

    @Override
    public void onImuStateChanged(ImuStateEvent imuStateEvent) {
        switch (imuStateEvent.getImuState())  {
            case NotAvailable:
            case NeedsMovement:
            case Running:
                sensorStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Success), "ImuStateChanged:" + imuStateEvent.getImuState().name());
                break;
            case ErrorHasBeenDetected:
            case ExcessiveBiasHasBeenDetected:
                sensorStatusUpdate = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Error), "ImuStateChanged:" + imuStateEvent.getImuState().name());
                break;
        }
    }

    @Override
    public void onPowerUpdate(PowerSourceState powerSourceState) {
        int powerLevel = powerSourceState.getBatteryLevel();
        String batteryLevel = powerLevel + "%";

        // unknown, for example when no battery is used
        if (powerLevel < 1) {
            if(powerSourceState.isCharging()) {
                batteryLevel = "Charging";
            } else {
                batteryLevel = "Unknown";
            }
        }

        powerSourceUpdate  = new StatusUpdate(this, new ReturnCode(DriverReturnCode.Success), batteryLevel);
        triggerStatusUpdateListener(new Action<StatusUpdateListener>() {
            @Override
            public void run(StatusUpdateListener item) {
                item.onPowerSourceStateUpdate(powerSourceUpdate);
            }
        });
    }

    public void marksPoints(String pointName) {
        try {
            positionUpdates.put(createPositionLog(String.format("Start Position Marking:%s", pointName)));
            this.pointName = pointName;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void clearMarking() {
        try {
            positionUpdates.put(createPositionLog(String.format("End Position Marking:%s", pointName)));
            pointName = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public String getPointName() {
        return pointName;
    }

    private static String createPositionLog(String msg) {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault());
        return String.format("%s,%s", simpleDateFormat.format(new Date()), msg);
    }
    
    public void goStatic(boolean bIsGoStatic)
    {
    	ReturnCode retCode = catalystFacade.setMotionState(bIsGoStatic);
    	if (retCode.getCode() != DriverReturnCode.Success)
        {
    		final StatusUpdate status = new StatusUpdate(this, new ReturnCode(retCode.getCode()), "Error when changing the goStatic");
        	triggerStatusUpdateListener(new Action<MainModel.StatusUpdateListener>() {

                @Override
                public void run(StatusUpdateListener item) {
                    item.onSurveyStatusUpdate(status);
                }
            });
        }
        else {
            try {
                if (this.isGoStatic == bIsGoStatic) return;
                this.isGoStatic = bIsGoStatic;

                positionUpdates.put(createPositionLog(bIsGoStatic ? "Start static mode" : "Stop static mode"));
        } catch (InterruptedException e) {
            e.printStackTrace();
            }
        }
    }

    private static boolean isValidNtripServerConfig(Properties properties)
    {
        try {

            if(properties.getProperty(NtripServer).isEmpty()) {
                return false;
            }
            if(properties.getProperty(NtripPort).isEmpty()) {
                return false;
            }
            Integer.parseInt(properties.getProperty(NtripPort));
        } catch (Exception e) {
            return false;
        }
        return true;
    }
    private static boolean isNtripUserConfig(Properties properties)
    {
        try {
            if(properties.getProperty(NtripUser).isEmpty()) {
                return false;
            }
            if(properties.getProperty(NtripPassword).isEmpty()) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private static boolean isMountPointConfig(Properties properties)
    {
        try {
            if(properties.getProperty(NtripSource).isEmpty()) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private void runOnThread(Runnable runnable, boolean newThread) {
        if(newThread)
        {
            new Thread(runnable).start();
        } else {
            threadExecutor.execute(runnable);
        }
    }

    public String getTpsdkVersion() {
        return catalystFacade.version();
    }

    public String getHostSerial() {
        return catalystFacade.hostSerial();
    }

    public int getExternalReceiverBatteryLevel()
    {
        if (catalystFacade == null)
            return 0;

        return catalystFacade.getExternalReceiverBatteryLevel();
    }

    public String installRTXSubscription(String optionCode)
    {
        ReturnCode errorCode = catalystFacade.installSubscriptions(optionCode);

        if (errorCode.getCode() == DriverReturnCode.Success)
        {
            return null;
        }

        return errorCode.getCode().toString();
    }

    public String getRTXSubscriptions()
    {
        List<SubscriptionType> container = new LinkedList<>();
        ReturnCode errorCode = catalystFacade.getRTXSubscriptions(container);

        if (errorCode.getCode() == DriverReturnCode.Success)
        {
            StringBuilder result = new StringBuilder();

            for (SubscriptionType subscription : container)
            {
                result.append(subscription.toString()).append(" ");
            }
            return result.toString();
        }

        return errorCode.toString();
    }

    public void Maillog(Activity activity)
    {
        try {
            FileReader reader = new FileReader(getPositionLogFile());
            BufferedReader br = new BufferedReader(reader);

            String currentLine;
            StringBuilder text = new StringBuilder();
            while ((currentLine = br.readLine()) != null)
            {
                text.append(currentLine);
            }
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);
            sendIntent.putExtra(Intent.EXTRA_TEXT, text.toString());
            sendIntent.setType("text/plain");

            Intent shareIntent = Intent.createChooser(sendIntent, null);
            activity.startActivity(shareIntent);
        }
        catch(Exception ex) {
            //Ignoring exceptions
        }
    }
}
