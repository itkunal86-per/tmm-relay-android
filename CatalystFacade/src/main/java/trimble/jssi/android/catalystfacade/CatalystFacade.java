package trimble.jssi.android.catalystfacade;

import static trimble.jssi.interfaces.power.PowerSourceType.ExternalPower;
import static trimble.jssi.interfaces.power.PowerSourceType.InternalBattery;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Hashtable;
import java.util.List;
import java.util.TimeZone;

import trimble.jssi.android.communicators.ASerialCommunicator;
import trimble.jssi.android.communicators.BluetoothCommunicator;
import trimble.jssi.android.communicators.EMPowerTCPCommunicator;
import trimble.jssi.android.communicators.TCPCommunicator;
import trimble.jssi.connection.ConnectionParameterType;
import trimble.jssi.connection.ConnectionSettings;
import trimble.jssi.connection.ConnectionState;
import trimble.jssi.connection.ConnectionType;
import trimble.jssi.connection.ICommunicator;
import trimble.jssi.connection.IConnectionParameter;
import trimble.jssi.connection.IConnectionParameterAndroidServiceSettings;
import trimble.jssi.connection.IConnectionParameterExternalSettings;
import trimble.jssi.connection.IConnectionStateChangedListener;
import trimble.jssi.driver.androidservice.catalyst.CatalystDriver;
import trimble.jssi.driver.proxydriver.interfaces.power.SsiPower;
import trimble.jssi.drivercommon.connection.CommunicatorBase;
import trimble.jssi.drivermanagement.impl.ITMMLicenseServiceConnector;
import trimble.jssi.drivermanagement.impl.TMMLicenseServiceConnectorInstanceFactory;
import trimble.jssi.interfaces.AlreadyConnectedException;
import trimble.jssi.interfaces.DriverManagerInstance;
import trimble.jssi.interfaces.IDriver;
import trimble.jssi.interfaces.IDriverManager;
import trimble.jssi.interfaces.IErrorOccurredListener;
import trimble.jssi.interfaces.ISensor;
import trimble.jssi.interfaces.InternetNotConnectedException;
import trimble.jssi.interfaces.MappingTable;
import trimble.jssi.interfaces.OperationCanceledException;
import trimble.jssi.interfaces.OutOfRangeException;
import trimble.jssi.interfaces.SensorOccupiedException;
import trimble.jssi.interfaces.SsiException;
import trimble.jssi.interfaces.SsiInterfaceType;
import trimble.jssi.interfaces.USBConnectionException;
import trimble.jssi.interfaces.optioncode.ISsiOptionCode;
import trimble.jssi.interfaces.gnss.Coordinates;
import trimble.jssi.interfaces.gnss.PositionRate;
import trimble.jssi.interfaces.gnss.antenna.AntennaHeightConfiguration;
import trimble.jssi.interfaces.gnss.antenna.AntennaType;
import trimble.jssi.interfaces.gnss.antenna.ISsiAntenna;
import trimble.jssi.interfaces.gnss.antenna.MeasurementMethod;
import trimble.jssi.interfaces.gnss.inertialnavigation.ISsiInertialNavigation;
import trimble.jssi.interfaces.gnss.inertialnavigation.InertialNavigationMode;
import trimble.jssi.interfaces.gnss.positioning.GNSSObservationContainer;
import trimble.jssi.interfaces.gnss.positioning.GNSSObservationType;
import trimble.jssi.interfaces.gnss.positioning.IMUAlignmentStatus;
import trimble.jssi.interfaces.gnss.positioning.IPositionListener;
import trimble.jssi.interfaces.gnss.positioning.IPositioningParameter;
import trimble.jssi.interfaces.gnss.positioning.IPositioningParameterMotionState;
import trimble.jssi.interfaces.gnss.positioning.IPositioningParameterRate;
import trimble.jssi.interfaces.gnss.positioning.IReferenceSystem;
import trimble.jssi.interfaces.gnss.positioning.ISsiPositioning;
import trimble.jssi.interfaces.gnss.positioning.MotionState;
import trimble.jssi.interfaces.gnss.positioning.PositioningObservationEvent;
import trimble.jssi.interfaces.gnss.positioning.PositioningParameterType;
import trimble.jssi.interfaces.gnss.positioning.PositioningSettings;
import trimble.jssi.interfaces.gnss.positioning.RTKErrorStatus;
import trimble.jssi.interfaces.gnss.positioning.RTKProgressStatus;
import trimble.jssi.interfaces.gnss.positioning.SolutionType;
import trimble.jssi.interfaces.gnss.positioning.GroundPositionType;
import trimble.jssi.interfaces.gnss.rtk.CorrectionDataFormat;
import trimble.jssi.interfaces.gnss.rtk.CorrectionDataSourceType;
import trimble.jssi.interfaces.gnss.rtk.GPRSNTRIPStartStatus;
import trimble.jssi.interfaces.gnss.rtk.IConnectionStatusListener;
import trimble.jssi.interfaces.gnss.rtk.ICorrectionDataReceivedListener;
import trimble.jssi.interfaces.gnss.rtk.ICorrectionDataSource;
import trimble.jssi.interfaces.gnss.rtk.ICorrectionDataSourceDirectIPSettings;
import trimble.jssi.interfaces.gnss.rtk.ICorrectionDataSourceNTRIPSettings;
import trimble.jssi.interfaces.gnss.rtk.ICorrectionDataSourceRTXSettings;
import trimble.jssi.interfaces.gnss.rtk.ICorrectionDataSourceRTXViaIpSettings;
import trimble.jssi.interfaces.gnss.rtk.ICorrectionDataSourceTCPSettings;
import trimble.jssi.interfaces.gnss.rtk.ICorrectionDataSourceTrimbleHubSettings;
import trimble.jssi.interfaces.gnss.rtk.IDatumTransformationTimeDependent;
import trimble.jssi.interfaces.gnss.rtk.ILinkParameter;
import trimble.jssi.interfaces.gnss.rtk.ILinkParameterControllerInternetSettings;
import trimble.jssi.interfaces.gnss.rtk.ILinkParameterRTXSatelliteSettings;
import trimble.jssi.interfaces.gnss.rtk.IReferenceStationUpdateListener;
import trimble.jssi.interfaces.gnss.rtk.ISsiRTKSurvey;
import trimble.jssi.interfaces.gnss.rtk.ISurveyStateListener;
import trimble.jssi.interfaces.gnss.rtk.IVerticalAdjustment;
import trimble.jssi.interfaces.gnss.rtk.LinkParameterType;
import trimble.jssi.interfaces.gnss.rtk.NTRIPSetupException;
import trimble.jssi.interfaces.gnss.rtk.RTXDataStream;
import trimble.jssi.interfaces.gnss.rtk.ReferenceFrameEpoch;
import trimble.jssi.interfaces.gnss.rtk.SurveySettings;
import trimble.jssi.interfaces.gnss.rtk.SurveyState;
import trimble.jssi.interfaces.gnss.satellites.ISatellite;
import trimble.jssi.interfaces.gnss.satellites.ISatelliteMaskParameter;
import trimble.jssi.interfaces.gnss.satellites.ISatelliteMaskParameterTrackSBAS;
import trimble.jssi.interfaces.gnss.satellites.ISatelliteUpdateListener;
import trimble.jssi.interfaces.gnss.satellites.ISsiSatellites;
import trimble.jssi.interfaces.gnss.satellites.SatelliteMask;
import trimble.jssi.interfaces.gnss.satellites.SatelliteMaskParameterType;
import trimble.jssi.interfaces.gnss.subscriptions.SubscriptionTypeGnss;
import trimble.jssi.interfaces.power.IBattery;
import trimble.jssi.interfaces.power.ICurrentPowerSourceChangedListener;
import trimble.jssi.interfaces.power.IPowerSource;
import trimble.jssi.interfaces.power.ISsiPower;
import trimble.jssi.interfaces.power.PowerSourceEvent;
import trimble.jssi.interfaces.sensorproperties.ISensorFirmwareProperty;
import trimble.jssi.interfaces.sensorproperties.ISensorLicensedProperty;
import trimble.jssi.interfaces.sensorproperties.ISensorNameProperty;
import trimble.jssi.interfaces.sensorproperties.ISensorSerialNumberProperty;
import trimble.jssi.interfaces.sensorproperties.ISsiSensorProperties;
import trimble.jssi.interfaces.subscriptions.ISsiSubscriptions;
import trimble.jssi.interfaces.subscriptions.SubscriptionType;
import trimble.licensing.v2.ILicenseGroup;
import trimble.licensing.v2.ILicenseInfo;
import trimble.licensing.v2.ITrimbleUser;
import trimble.licensing.v2.LicenseException;
import trimble.licensing.v2.ILicensing;
import trimble.licensing.LicensingFactory;

/**
 * CatalystFacade is a one layer interface to the Catalyst Antenna. It provides
 * functions and event delegates to control the antenna and get antenna
 * information.
 */
public class CatalystFacade {
    public static final String TAG = "JCatalystFacade";
    private IDriver driver;

    private ISensor sensor;

    private ConnectionSettings connectionSettings;

    private double reducedAntennaHeight = 0.0;
    private long receivedCorrectionData = 0;
    private int stationId = 0;
    private final ArrayList<ICatalystEventListener> catalystEventListeners = new ArrayList<>();
    private final String appGuid;
    private final Context context;
    private IDriverManager driverManager;
    private DriverType currentDeviceType;
    private boolean rtkCorrectionHubStarted;
    private Runnable restartCorrectionHub;
    private boolean trimbleIdLoginSuccessful;

    static {
        System.loadLibrary("c++_shared");
        
        // Datum transformation
        System.loadLibrary("GeodeticX");
        System.loadLibrary("TDDTransformationLibJNI");               // Datum transformation
        System.loadLibrary("CsdManagement");                              // Datum transformation

        /*==============================================*/
        //  GNSS for Trimble RSeries and  SP60, SP80
        /*==============================================*/
        
        // Shared components for native driver
        System.loadLibrary("Trimble.Ssi.Interfaces.Common");
        System.loadLibrary("Trimble.Ssi.Interfaces.GNSS");
        System.loadLibrary("DRV_TrimbleCommon"); 
        System.loadLibrary("Trimble.Ssi.Driver.DriverCommon.Common");
        System.loadLibrary("Trimble.Ssi.Driver.DriverCommon.GNSS");
        System.loadLibrary("Trimble.Ssi.DriverManagement");

        // glue layer
        System.loadLibrary("Trimble.Ssi.Wrapped.Common"); 
        System.loadLibrary("Trimble.Ssi.Wrapped.GNSS");
        
        // Common for native drivers
        System.loadLibrary("Trimble.Ssi.Driver.CarpoBased.Common"); 
        
        // GNSS for Trimble RSeries and  SP60, SP80
        System.loadLibrary("Trimble.Ssi.Driver.CarpoBased.GNSS");
        System.loadLibrary("DRV_TrimCom"); 
        System.loadLibrary("Trimble.Ssi.Driver.CarpoBased.Driver.RSeries");
        System.loadLibrary("DRV_SP80");                                      // SP60 and SP80 driver
        System.loadLibrary("Trimble.Ssi.Driver.CarpoBased.Driver.SP80");

        System.loadLibrary("Trimble.Ssi.Driver.Mock.Common");
        System.loadLibrary("Trimble.Ssi.Driver.Mock.GNSS");                  // Mock GNSS
    }

    /**
     * Catalyst facade constructor
     * 
     * @param appGuid
     *            Your application guid you get from Trimble
     * @param context
     *            Your application context
     */
    public CatalystFacade(String appGuid, Context context) {
        this.appGuid = appGuid;
        this.context = context;
        try {
            File file = new File(context.getExternalFilesDir(null).getAbsolutePath() + "/log");
            this.driverManager = DriverManagerInstance.getInstance(DriverManagerInstance.LicensingMode.V2,context,appGuid);
            driverManager.setDriverLogFilePath(file.getAbsolutePath());
        } catch (Exception e) {
            this.driverManager = null;
        }
    }

    /**
     * Host serial
     */
    public String hostSerial() {
        try {
            return driverManager.getHostSerial();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Interface version of the driver
     */
    public String version() {
        return DriverManagerInstance.getSDKVersion();
    }

    /**
     * Load the subscription without TMM
     *
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode loadSubscription() {
        //Releases any already loaded driver
        releaseDriver();
        final ReturnCode returnCode = new ReturnCode(DriverReturnCode.Success);
        trimbleIdLoginSuccessful = false;
        try {
            setTrimbleUserAndReloadDrivers(null);
        } catch (Exception e) {
          logException("License", e);
        }
        return returnCode;
    }

    /**
     * Load the subscription exposed from Trimble Mobile Manager
     * 
     * @param userTrimbleId Trimble User Id
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode loadSubscriptionFromTrimbleMobileManager(String userTrimbleId) {
        final ReturnCode returnCode = new ReturnCode(DriverReturnCode.Error);
        //Releases any already loaded driver
        releaseDriver();
        ITMMLicenseServiceConnector serviceConnector = TMMLicenseServiceConnectorInstanceFactory.getInstance(context);
        try {
            this.driverManager = DriverManagerInstance.getInstance(DriverManagerInstance.LicensingMode.V2, context, appGuid);
            serviceConnector.downloadLicense(appGuid,userTrimbleId);
            returnCode.setReturnCode(DriverReturnCode.Success);
            setTrimbleUserAndReloadDrivers(userTrimbleId);
        } catch (LicenseException e) {
            returnCode.setReturnCode(DriverReturnCode.ErrorNoLicense);
            try {
                setTrimbleUserAndReloadDrivers(null);
            } catch (Exception e1) {
                logException("License", e);
            }
            logException("License", e);
        } catch (Exception e) {
            returnCode.setReturnCode(DriverReturnCode.Error);
            try {
                setTrimbleUserAndReloadDrivers(null);
            } catch (Exception e1) {
                logException("License", e);
            }
            logException("License", e);
        }
        trimbleIdLoginSuccessful = returnCode.getCode() == DriverReturnCode.Success;
        return returnCode;
    }

    /**
     * Load and push a device subscription to the device and get license information
     * @param deviceLicense The device license content as string
     * @return ReturnObject with the driver return code and SubscriptionDetails @see DriverReturnCode
     */
    public ReturnObject<SubscriptionDetails> loadDeviceSubscription(String deviceLicense) {
        //Releases any already loaded driver
        releaseDriver();
        trimbleIdLoginSuccessful = false;
        String subscriptionName = "";
        Date issueDate = new Date (0);
        Date expiryDate = new Date (0);
        DriverReturnCode returnCode = DriverReturnCode.Success;
        try {
            ILicensing licensing = LicensingFactory.createV2Licensing(context);
            String appID = licensing.installDeviceLicense(deviceLicense);
            this.driverManager = DriverManagerInstance.getInstance(DriverManagerInstance.LicensingMode.V2Legacy, context, appID,appID);
            setTrimbleUserAndReloadDrivers(appID);

            ITrimbleUser user = licensing.login(appID);
            ILicenseGroup licenseGroup = licensing.getLicenseGroup(user, appID);

            issueDate = getDateFromUtcString(getDateString(licenseGroup.getIssued()));
            List<ILicenseInfo> licenseInfoList = licenseGroup.getLicenses();
            for(ILicenseInfo licenseInfo : licenseInfoList) {
                if(licenseInfo.getLicenseFeature("Receiver.Catalyst") != null) {
                    subscriptionName = licenseInfo.getName();
                    expiryDate = getDateFromUtcString(getDateString(licenseInfo.getUTCExpiryDateTime()));
                    break;
                }
            }
        } catch (Exception e) {
            returnCode = DriverReturnCode.ErrorNoLicense;
            logException("License", e);
        }
        return new ReturnObject<>(returnCode, new SubscriptionDetails (subscriptionName, issueDate, expiryDate));
    }

    private static String getDateString(String dateString) {
        return dateString.contains("T") ? dateString: dateString+"T00:00:00";
    }

    private Date getDateFromUtcString(String utcString) throws ParseException {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat ("yyyy-MM-dd'T'HH:mm:ss'Z'");
            dateFormat.setTimeZone (TimeZone.getTimeZone ("UTC"));
            Date utcDate = dateFormat.parse (utcString + "Z");

            TimeZone localTimeZone = TimeZone.getDefault ();
            dateFormat.setTimeZone (localTimeZone);

            String localTimeString = dateFormat.format (utcDate);
            return dateFormat.parse (localTimeString);
        } catch (Exception e) {
            throw e;
        }
    }

    private void setTrimbleUserAndReloadDrivers(String trimbleId) throws Exception
    {
        if (sensor != null && sensor.getConnectionState() == ConnectionState.Connected)
        {
            // disconnect so that we can free existing drivers
            disconnectFromSensor();
        }

        driverManager.setTrimbleUser(trimbleId);
    }

    /**
     *
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode releaseDriver()
    {
        ReturnCode returnCode = new ReturnCode(DriverReturnCode.Success);
        try
        {
            if (driver == null)
            {
                return  new ReturnCode(DriverReturnCode.Error);
            }
            if (sensor != null && sensor.getConnectionState() == ConnectionState.Connected)
            {
                disconnectFromSensor();
            }
            
            driver = null;
        }
        catch (Exception e)
        {
            logException("ReleaseDriver", e);
            return  new ReturnCode(DriverReturnCode.Error);
        }
        return returnCode;
    }

    /**
     *
     * @param deviceType Device Driver Type
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode initDriver(DriverType deviceType) {
        try {
        	if(null != driver) {
        		releaseDriver();
        	}
        	
        	currentDeviceType = deviceType;
            driver = getDriver(deviceType);
            if (driver == null) {
                return new ReturnCode(DriverReturnCode.ErrorLoadingDriver);
            }
        } catch (OutOfRangeException e) {
            logException("LoadDriver", e);
            return new ReturnCode(DriverReturnCode.DeviceTypeNotSupported);
        } catch (Exception e) {
            logException("LoadDriver", e);
            return new ReturnCode(DriverReturnCode.ErrorLoadingDriver);
        }
        return new ReturnCode(DriverReturnCode.Success);
    }

    final MappingTable<DriverType, String> deviceTypeMap = new MappingTable<DriverType, String>() {

        @Override
        public void fill() {
            add(DriverType.TrimbleGNSS, "TrimbleRSeries");
            add(DriverType.Catalyst, "TrimbleCatalyst");
            add(DriverType.Mock,"TrimbleMockGNSS");
            add(DriverType.SpectraPrecision,"SpectraPrecisionGNSS");
            add(DriverType.EM100,"TrimbleRSeries");
            add(DriverType.TDC150,"SpectraPrecisionGNSS");
        }
    };

    protected IDriver getDriver(final DriverType type) {
        this.driver = null;
        String driverLicenseName = deviceTypeMap.mapKeyToValue(type);
        switch (type) {
            case Catalyst:
                driverManager.registerDriver(new CatalystDriver());
                break;
            case TrimbleGNSS:
            case EM100:
                driverManager.loadDriver("Trimble.Ssi.Driver.CarpoBased.Driver.RSeries");
                break;
            case Mock:
                driverManager.loadDriver("Trimble.Ssi.Driver.Mock.GNSS");
                break;
            case SpectraPrecision:
            case TDC150:
                driverManager.loadDriver("Trimble.Ssi.Driver.CarpoBased.Driver.SP80");
                break;
            default:
                logError("Invalid device type", "Specified device type is invalid");
                break;
        }

        List<IDriver> drivers = driverManager.getLoadedDrivers();
        for (IDriver driver : drivers) {
            if (driver.getInformation().getLicenseName().equalsIgnoreCase(driverLicenseName)) {
                this.driver = driver;
                break;
            }
        }
        return driver;
    }

    public PowerSourceState getPowerSourceState() {
        if (sensor == null)
            return null;

        SsiPower power = (SsiPower)sensor.getInterface(SsiInterfaceType.SsiPower);
        if (power == null)
            return null;

        IPowerSource powerSource = power.getCurrentPowerSource();
        if (powerSource == null)
            return null;

        if (powerSource.getPowerSourceType() == ExternalPower) {
            return new PowerSourceState(-1, true);
        } else if (powerSource.getPowerSourceType() == InternalBattery) {
            IBattery battery = (IBattery)powerSource;
            return new PowerSourceState(battery.getLevel(), false);
        }

        return null;
    }

    public boolean isSensorConnected() {
        if (sensor != null)
            return sensor.getConnectionState() == ConnectionState.Connected;
        else {
            return false;
        }
    }

    protected void logException(String tag, Exception exception) {
        android.util.Log.e(TAG,tag+":",exception);
    }

    protected void logInfo(String tag, String message) {
        android.util.Log.i(TAG,tag+":"+message);
    }

    protected void logError(String tag, String message) {
        android.util.Log.e(TAG,tag+":"+message);
    }

    protected void logDebug(String tag, String message) {
        android.util.Log.d(TAG,tag+":"+message);
    }

    /**
     * Connect to the sensor
     * 
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode connect() {
        switch (currentDeviceType)
        {
            case Catalyst:
                Hashtable<String, Object> catalystParams = new Hashtable<>();
                catalystParams.put("context", context);
                catalystParams.put("packagename", context.getPackageName());
                return connectToSensor(ConnectionType.AndroidService, catalystParams);
            case EM100:
                Hashtable<String, Object> em100params = new Hashtable<>();
                CommunicatorBase emPowerTCPCommunicator = new EMPowerTCPCommunicator(this.context);
                em100params.put("communicator", emPowerTCPCommunicator);
                return connectToSensor(ConnectionType.EMPower, em100params);
            case TDC150:
                Hashtable<String, Object> tdc150params = new Hashtable<>();
                CommunicatorBase serialCommunicator = new ASerialCommunicator();
                tdc150params.put("communicator", serialCommunicator);
                return connectToSensor(ConnectionType.OnboardSerial, tdc150params);
            default:
                return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }

    }

    /**
     * Connect via Bluetooth to the sensor
     * 
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode connectViaBluetooth(String address) {
        if(currentDeviceType != DriverType.TrimbleGNSS && currentDeviceType != DriverType.SpectraPrecision) {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }
        if(address.isEmpty()) {
            return new ReturnCode(DriverReturnCode.ErrorInvalidParameter);
        }

        BluetoothDevice device;
        try {
            device = ((BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter().getRemoteDevice(address);
        } catch (Exception ex) {
            return new ReturnCode(DriverReturnCode.ErrorInvalidParameter);
        }

        Hashtable<String, Object> params = new Hashtable<>();
        CommunicatorBase communicator = new BluetoothCommunicator(device, this.context);
        params.put("communicator", communicator);
        return connectToSensor(ConnectionType.Bluetooth, params);
    }

    /**
     * Connect mock driver
     *
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode connectMock() {
        return connectToSensor(ConnectionType.Internal, new Hashtable<>());
    }
    /**
     * Connect via Wifi to the sensor
     * 
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode connectViaWifi(String tcpIpAddress, String tcpIpPort) {
        if(tcpIpAddress.isEmpty() || tcpIpPort.isEmpty()) {
            return new ReturnCode(DriverReturnCode.ErrorInvalidParameter);
        }

        Hashtable<String, Object> params = new Hashtable<>();
        try {
            CommunicatorBase communicator = new TCPCommunicator(tcpIpAddress, Integer.parseInt(tcpIpPort), context,false);
            params.put("communicator", communicator);
            return connectToSensor(ConnectionType.TCPIP, params);
        } catch (Exception ex) {
            return new ReturnCode(DriverReturnCode.ErrorInvalidParameter);
        }
    }

    /**
     * Connect to the sensor
     *
     * @param connType  connection type
     * @param parameters Map with Connection Parameters
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    protected ReturnCode connectToSensor(ConnectionType connType, Hashtable<String, Object> parameters) {
        List<IConnectionParameter> connectionParams = new ArrayList<>();
        try {
            if (sensor != null) {
                if (sensor.getConnectionState() == ConnectionState.Connected || sensor.getConnectionState() == ConnectionState.Connecting
                        || sensor.getConnectionState() == ConnectionState.Disconnecting) {
                    return new ReturnCode(DriverReturnCode.ErrorAlreadyConnected);
                }
                if (sensor.getConnectionState() == ConnectionState.LinkDown) {
                    disconnectFromSensor();
                }
            }

            if (null == parameters)
                return new ReturnCode(DriverReturnCode.ErrorInvalidParameter);

            sensor = driver.createSensor();
            if(connType == ConnectionType.AndroidService)
            {
                IConnectionParameterAndroidServiceSettings serviceSettings = (IConnectionParameterAndroidServiceSettings) sensor
                        .createConnectionParameter(ConnectionParameterType.AndroidServiceSettings);

                serviceSettings.setContext(parameters.get("context"));
                serviceSettings.setPackageName((String) parameters.get("packagename"));
                connectionParams.add(serviceSettings);
            }
            else  if(connType == ConnectionType.TCPIP ||
                     connType == ConnectionType.Bluetooth ||
                     connType == ConnectionType.EMPower ||
                     connType == ConnectionType.OnboardSerial)
            {
                ConnectionParameterType connParamType = ConnectionParameterType.BluetoothSettings;
                switch (connType)
                {
                    case Bluetooth:
                        connParamType = ConnectionParameterType.BluetoothSettings;
                        break;
                    case TCPIP:
                    case EMPower:
                        connParamType = ConnectionParameterType.TCPIPSettings;
                        break;
                    case OnboardSerial:
                        connParamType = ConnectionParameterType.SerialSettings;
                        break;
                }
                IConnectionParameterExternalSettings settings =
                        (IConnectionParameterExternalSettings) sensor.createConnectionParameter(connParamType);
                settings.setCommunicator((ICommunicator) parameters.get("communicator"));
                connectionParams.add(settings);
            } else if(connType == ConnectionType.Internal) {
                // No params
            } else {
                return new ReturnCode(DriverReturnCode.ErrorInvalidParameter);
            }
            connectionSettings = new ConnectionSettings(connType, connectionParams);
            subscribeSensorEvents();
            sensor.connect(connectionSettings);
            subscribe();
            enableSBASTracking();
            setInertialNavigationMode(InertialNavigationMode.Off);
        } catch (OperationCanceledException e) {
            logException("Connect", e);
            //Disconnect forcefully if the exception happened after successful connection
            if(isSensorConnected()) {
                disconnectFromSensor();
            }
            return new ReturnCode(DriverReturnCode.ErrorCancelledFromUser);
        } catch (SensorOccupiedException e) {
            logException("Connect", e);
            //Disconnect forcefully if the exception happened after successful connection
            if(isSensorConnected()) {
                disconnectFromSensor();
            }
            return new ReturnCode(DriverReturnCode.ErrorSensorAlreadyInUse);
        } catch (Exception e) {
            logException("Connect", e);
            //Disconnect forcefully if the exception happened after successful connection
            if(isSensorConnected()) {
                disconnectFromSensor();
            }
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }
        return new ReturnCode(DriverReturnCode.Success);
    }

    /**
     * Set the inertial navigation mode.
     * @param navigationMode Inertial navigation mode
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode setInertialNavigationMode(InertialNavigationMode navigationMode) {
        if (!isSensorConnected()) {
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }
        ISsiInertialNavigation inertialNavigation = (ISsiInertialNavigation) sensor.getInterface(SsiInterfaceType.SsiInertialNavigation);
        if(inertialNavigation == null) {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }
        if(!inertialNavigation.isSupported(navigationMode)){
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }
        inertialNavigation.setInertialNavigationMode(navigationMode);
        return new ReturnCode(DriverReturnCode.Success);
    }

    /**
     *  Get the inertial navigation mode
     *
     *  @return the current inertial navigation mode
     */
    public InertialNavigationMode getInertialNavigationMode()
    {
        if(!isSensorConnected()) {
            return InertialNavigationMode.Off;
        }
        ISsiInertialNavigation ssiInertialNavigation = (ISsiInertialNavigation) sensor.getInterface(SsiInterfaceType.SsiInertialNavigation);
        if(ssiInertialNavigation == null) {
            return InertialNavigationMode.Off;
        }
        return ssiInertialNavigation.getInertialNavigationMode();
    }

    /**
     * Reconnect with known parameters
     * 
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode reconnectToSensor() {
        try {
            unsubscribeSensorEvents();
            unsubscribe();
            subscribeSensorEvents();
            sensor.connect(connectionSettings);
            subscribe();
        } catch (OperationCanceledException e) {
            logException("Reconnect", e);
            return new ReturnCode(DriverReturnCode.ErrorCancelledFromUser);
        } catch (AlreadyConnectedException e) {
            logException("Reconnect", e);
            return new ReturnCode(DriverReturnCode.ErrorAlreadyConnected);
        } catch (Exception e) {
            logException("Reconnect", e);
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }
        return new ReturnCode(DriverReturnCode.Success);
    }

    /**
     * Disconnect
     * 
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode disconnectFromSensor() {
        try {
            unsubscribe();
            if(sensor != null)
            {
                sensor.disconnect();
                unsubscribeSensorEvents();
                driverManager.freeSensor(driver, sensor);
                sensor = null;
            }
        } catch (OperationCanceledException e) {
            logException("Disconnect", e);
            return new ReturnCode(DriverReturnCode.ErrorCancelledFromUser);
        } catch (Exception e) {
            logException("Disconnect", e);
            return new ReturnCode(DriverReturnCode.Error);
        }
        return new ReturnCode(DriverReturnCode.Success);
    }

    public void addCatalystEventListener(ICatalystEventListener catalystEventListener) {
        synchronized (catalystEventListeners) {
            if (!catalystEventListeners.contains(catalystEventListener))
                catalystEventListeners.add(catalystEventListener);
        }
    }

    public void removeCatalystEventListener(ICatalystEventListener catalystEventListener) {
        synchronized (catalystEventListeners) {
            catalystEventListeners.remove(catalystEventListener);
        }
    }

    private void subscribeSensorEvents() {
        if (sensor == null) {
            return;
        }
        sensor.addConnectionStateChangedListener(connectionStateChangedListener);

        sensor.addErrorOccurredListener(errorOccurredListener);
    }

    private void unsubscribeSensorEvents() {
        if (sensor == null) {
            return;
        }
        sensor.removeConnectionStateChangedListener(connectionStateChangedListener);
        sensor.removeErrorOccurredListener(errorOccurredListener);
    }

    private void subscribe() {

        if (sensor == null) {
            return;
        }

        ISsiRTKSurvey rtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);
        if (rtkSurvey != null) {
            rtkSurvey.addCorrectionDataReceivedListener(correctionDataReceivedListener);
            rtkSurvey.addReferenceStationUpdateListener(referenceStationUpdateListener);
            rtkSurvey.addSurveyStateListener(surveyStateListener);
        }

        ISsiPositioning ssiPositioning = (ISsiPositioning) sensor.getInterface(SsiInterfaceType.SsiPositioning);
        if (ssiPositioning != null) {
            ssiPositioning.addPositionListener(positionListener);
            ssiPositioning.startPositioning(new PositioningSettings());
        }

        ISsiSatellites ssiSatellites = (ISsiSatellites) sensor.getInterface(SsiInterfaceType.SsiSatellites);
        if (ssiSatellites != null) {
            ssiSatellites.addSatelliteUpdateListener(satelliteUpdateListener);
            ssiSatellites.startSatelliteStreaming();
        }

        ISsiPower ssiPower = (ISsiPower) sensor.getInterface(SsiInterfaceType.SsiPower);
        if (ssiPower != null) {
            ssiPower.addCurrentPowerSourceChangedListener(powerUpdateListener);
        }
    }

    private void unsubscribe() {

        if (sensor == null) {
            return;
        }

        ISsiRTKSurvey rtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);
        if (rtkSurvey != null) {
            rtkSurvey.removeCorrectionDataReceivedListener(correctionDataReceivedListener);
            rtkSurvey.removeReferenceStationUpdateListener(referenceStationUpdateListener);
            rtkSurvey.removeSurveyStateListener(surveyStateListener);
        }

        ISsiPositioning ssiPositioning = (ISsiPositioning) sensor.getInterface(SsiInterfaceType.SsiPositioning);
        if (ssiPositioning != null) {
            ssiPositioning.removePositionListener(positionListener);
            ssiPositioning.stopPositioning();
        }

        ISsiSatellites ssiSatellites = (ISsiSatellites) sensor.getInterface(SsiInterfaceType.SsiSatellites);
        if (ssiSatellites != null) {
            ssiSatellites.removeSatelliteUpdateListener(satelliteUpdateListener);
            ssiSatellites.stopSatelliteStreaming();
        }

        ISsiPower ssiPower = (ISsiPower) sensor.getInterface(SsiInterfaceType.SsiPower);
        if (ssiPower != null) {
            ssiPower.removeCurrentPowerSourceChangedListener(powerUpdateListener);
        }
    }

    private void enableSBASTracking() {
        if (sensor == null) {
            return;
        }

        ISsiSatellites ssiSatellites = (ISsiSatellites) sensor.getInterface(SsiInterfaceType.SsiSatellites);
        if (ssiSatellites != null) {
            if (ssiSatellites.isSupported(SatelliteMaskParameterType.TrackSBAS)) {
                SatelliteMask satelliteMask = ssiSatellites.getSatelliteMask();

                for (ISatelliteMaskParameter satelliteMaskParameter : satelliteMask.getParameters()) {
                    if (satelliteMaskParameter.getType() == SatelliteMaskParameterType.TrackSBAS) {
                        if (!((ISatelliteMaskParameterTrackSBAS) satelliteMaskParameter).getTrackSBAS()) {
                            ((ISatelliteMaskParameterTrackSBAS) satelliteMaskParameter).setTrackSBAS(true);
                            ssiSatellites.setSatelliteMask(satelliteMask);
                        }
                    }
                }
            }
        }
    }

    private void resetParameter() {
        receivedCorrectionData = 0;
        stationId = 0;
    }

    private final IConnectionStateChangedListener connectionStateChangedListener = connectionStateChangedEvent -> {
        if (new ArrayList<>(Arrays.asList(ConnectionState.LinkDown, ConnectionState.Disconnected, ConnectionState.Connected))
                .contains(connectionStateChangedEvent.getConnectionState())) {
            triggerStateChanged(connectionStateChangedEvent.getConnectionState());

            triggerSurveyTypeUpdate(SurveyType.None);
        }
    };

    private final IErrorOccurredListener errorOccurredListener = sensorErrorEvent -> {
        Exception ex = sensorErrorEvent.getException();
        if (ex instanceof USBConnectionException) {
            triggerUsbConnectionErrorOccured();
        } else if (ex instanceof LicenseException) {
            triggerSubscriptionHasExpired();
        }
    };

    private final ICorrectionDataReceivedListener correctionDataReceivedListener = correctionDataReceivedEvent -> CatalystFacade.this.receivedCorrectionData += correctionDataReceivedEvent.getReceivedBytes();

    private final IReferenceStationUpdateListener referenceStationUpdateListener = referenceStationUpdateEvent -> {
        try {
            CatalystFacade.this.stationId = referenceStationUpdateEvent.getReferenceStation().getID();
        } catch (Exception ex) {
            CatalystFacade.this.stationId = 0;
        }
    };

    private final ISurveyStateListener  surveyStateListener = surveyStateChangedEvent -> {
        if(surveyStateChangedEvent.getSurveyState() == SurveyState.Running) {
            triggerSurveyTypeUpdate(getCurrentSurvey());
        }
        else if (surveyStateChangedEvent.getSurveyState() == SurveyState.NotRunning) {
            triggerSurveyTypeUpdate(SurveyType.None);
        }

    };

    private final IPositionListener positionListener = new IPositionListener() {

        @Override
        public void onPostitionObservation(PositioningObservationEvent positioningObservationEvent) {
            GNSSObservationContainer obs = positioningObservationEvent.getObservationContainer();
           
            // Solution type
            SolutionType solutionType = SolutionType.Autonomous;
            if (obs.hasObservation(GNSSObservationType.SolutionType))
            {
              solutionType = obs.getObservation(GNSSObservationType.SolutionType).getSolutionType();
            }

            double latitude = Double.NaN;
            double longitude = Double.NaN;
            double height = Double.NaN;
            double elevation = Double.NaN;
            String geoidModel = "";
            GroundPositionType groundPositionType = GroundPositionType.Init;

            if (obs.hasObservation(GNSSObservationType.GroundCoordinate)) {
                Coordinates coordinates = obs.getObservation(GNSSObservationType.GroundCoordinate).getCoordinates();
                latitude = coordinates.getLatitude();
                longitude = coordinates.getLongitude();
                height = coordinates.getHeight();
                groundPositionType = obs.getObservation(GNSSObservationType.GroundCoordinate).getGroundPositionType();
            }
            else if (obs.hasObservation(GNSSObservationType.Coordinate)) {
                Coordinates coordinates = obs.getObservation(GNSSObservationType.Coordinate).getCoordinates();
                latitude = coordinates.getLatitude();
                longitude = coordinates.getLongitude();
                height = coordinates.getHeight();
            }

            if (obs.hasObservation(GNSSObservationType.GeoidUndulation)) {
                double N = obs.getObservation(GNSSObservationType.GeoidUndulation).getDeviation();
                if( !Double.isNaN(height) && !Double.isNaN(N))
                {
                    elevation = height - N;
                    geoidModel = obs.getObservation(GNSSObservationType.GeoidUndulation).getGeoidModel();
                }
            }


            double heading = Double.NaN;
            double horizontalVelocity = Double.NaN;
            double verticalVelocity = Double.NaN;

            if (obs.hasObservation(GNSSObservationType.Velocity)) {
                heading = obs.getObservation(GNSSObservationType.Velocity).getHeading();
                horizontalVelocity = obs.getObservation(GNSSObservationType.Velocity).getHorizontal();
                verticalVelocity = obs.getObservation(GNSSObservationType.Velocity).getVertical();
            }

            double haPrec = Double.NaN;
            double vaPrec = Double.NaN;

            if (obs.hasObservation(GNSSObservationType.Precision)) {
                haPrec = obs.getObservation(GNSSObservationType.Precision).getHorizontalPrecision();
                vaPrec = obs.getObservation(GNSSObservationType.Precision).getVerticalPrecision();
            }

            double sigmaSemiMajorAxis = Double.NaN;
            double sigmaSemiMinorAxis = Double.NaN;
            double sigmaOrientation = Double.NaN;

            if (obs.hasObservation(GNSSObservationType.PositionSigma)) {
                sigmaSemiMajorAxis = obs.getObservation(GNSSObservationType.PositionSigma).getSemiMajorAxis();
                sigmaSemiMinorAxis = obs.getObservation(GNSSObservationType.PositionSigma).getSemiMinorAxis();
                sigmaOrientation = obs.getObservation(GNSSObservationType.PositionSigma).getOrientation();
            }

            double Pdop = Double.NaN;
            double Hdop = Double.NaN;
            double Vdop = Double.NaN;

            if (obs.hasObservation(GNSSObservationType.DilutionOfPrecision)) {
                Pdop = obs.getObservation(GNSSObservationType.DilutionOfPrecision).getPDOP();
                Hdop = obs.getObservation(GNSSObservationType.DilutionOfPrecision).getHDOP();
                Vdop = obs.getObservation(GNSSObservationType.DilutionOfPrecision).getVDOP();
            } else if (obs.hasObservation(GNSSObservationType.ExtendedDilutionOfPrecision)) {
                Pdop = obs.getObservation(GNSSObservationType.ExtendedDilutionOfPrecision).getPDOP();
                Hdop = obs.getObservation(GNSSObservationType.ExtendedDilutionOfPrecision).getHDOP();
                Vdop = obs.getObservation(GNSSObservationType.ExtendedDilutionOfPrecision).getVDOP();
            }

            double pitch = Double.NaN;
            double roll = Double.NaN;
            double yaw = Double.NaN;
            double pitchPrecision = Double.NaN;
            double rollPrecision = Double.NaN;
            double yawPrecision = Double.NaN;

            if (obs.hasObservation (GNSSObservationType.Tilt))
            {
                pitch = obs.getObservation(GNSSObservationType.Tilt).getPitch();
                roll = obs.getObservation(GNSSObservationType.Tilt).getRoll();
                yaw = obs.getObservation(GNSSObservationType.Tilt).getYaw();

                if (obs.hasObservation(GNSSObservationType.TiltPrecision))
                {
                    pitchPrecision = obs.getObservation(GNSSObservationType.TiltPrecision).getPitchPrecision();
                    rollPrecision = obs.getObservation(GNSSObservationType.TiltPrecision).getRollPrecision();
                    yawPrecision = obs.getObservation(GNSSObservationType.TiltPrecision).getYawPrecision();
                }
            }

            int numberOfSatellites = 0;
            int numberOfTrackedSatellites = 0;
            if (obs.hasObservation(GNSSObservationType.Satellites)) {
                numberOfSatellites = obs.getObservation(GNSSObservationType.Satellites).getNumberOfSatellites();
                numberOfTrackedSatellites = obs.getObservation(GNSSObservationType.Satellites).getNumberOfTrackedSatellites();
            }

            double age = Double.NaN;
            if (obs.hasObservation(GNSSObservationType.CorrectionAge)) {
                age = obs.getObservation(GNSSObservationType.CorrectionAge).getAge().getTotalSeconds();
            }

            boolean datumTransformationApplied = obs.hasObservation(GNSSObservationType.ReferenceSource);

            IReferenceSystem sourceFrame = null;

            if (obs.hasObservation(GNSSObservationType.ReferenceSource)) {
                sourceFrame = obs.getObservation(GNSSObservationType.ReferenceSource).getReferenceSystem();
            }

            IReferenceSystem frame = null;

            if (obs.hasObservation(GNSSObservationType.ReferenceSystem)) {
                frame = obs.getObservation(GNSSObservationType.ReferenceSystem).getReferenceSystem();
            }

            Date gpsTime = new Date(0);
            Date utcTime = new Date(0);
            if (obs.hasObservation(GNSSObservationType.GPSTime)) {
                gpsTime = obs.getObservation(GNSSObservationType.GPSTime).getGpsTime();
                Calendar cal = Calendar.getInstance();
                cal.setTime(gpsTime);
                cal.add(Calendar.SECOND, -obs.getObservation(GNSSObservationType.GPSTime).getGpsUtcOffset());
                utcTime = cal.getTime();
            }

            int staticEpochs = 0;
            if (obs.hasObservation(GNSSObservationType.Epoch))
            {
                staticEpochs = obs.getObservation(GNSSObservationType.Epoch).getEpoch();
            }

            ImuState imuState = ImuState.NotAvailable;

            if (obs.hasObservation(GNSSObservationType.RTKStatus)) {
                RTKErrorStatus error = obs.getObservation(GNSSObservationType.RTKStatus).getRTKErrorStatus();
                switch (error)
                {
                    case NoRTXOffshore:
                        triggerSensorOutsideGeofence();
                        break;
                    case ImuError:
                        imuState = ImuState.ErrorHasBeenDetected;
                        break;
                    case ImuExcessiveBias:
                        imuState = ImuState.ExcessiveBiasHasBeenDetected;
                        break;
                }
            }

            if(imuState == ImuState.NotAvailable) {
                if(obs.hasObservation(GNSSObservationType.IMUAlignmentStatus)) {
                    IMUAlignmentStatus imu = obs.getObservation(GNSSObservationType.IMUAlignmentStatus).getAlignmentStatus();
                    switch (imu) {
                        case Fine:
                            imuState = ImuState.Running;
                            break;
                        case Unaligned:
                        case Coarse:
                            imuState = ImuState.NeedsMovement;
                            break;
                    }
                }
            }

            triggerPositionUpdate(solutionType, latitude, longitude, height, groundPositionType, heading, horizontalVelocity, verticalVelocity, haPrec, vaPrec,
                    sigmaSemiMajorAxis, sigmaSemiMinorAxis, sigmaOrientation, imuState, pitch, roll, yaw,
                    pitchPrecision, rollPrecision, yawPrecision, Pdop, Hdop, Vdop,
                    gpsTime, utcTime, numberOfSatellites, numberOfTrackedSatellites, staticEpochs, age, CatalystFacade.this.receivedCorrectionData, CatalystFacade.this.stationId,
                    datumTransformationApplied, sourceFrame, frame, elevation, geoidModel);

            if (obs.hasObservation(GNSSObservationType.RTKProgress)) {
                RTKProgressStatus status = obs.getObservation(GNSSObservationType.RTKProgress).getRTKProgressStatus();
                if(status == RTKProgressStatus.RtkAvailable || status == RTKProgressStatus.RtxAvailable) {
                    if(rtkCorrectionHubStarted && restartCorrectionHub != null) {
                        new Thread(restartCorrectionHub).start();
                    }

                    switch (status) {
                        case RtkAvailable:
                            triggerRtkServiceAvailable();
                            break;
                        case RtxAvailable:
                            triggerRtxServiceAvailable();
                            break;
                    }
                }
            }
        }
    };
    private boolean updateAntennaHeightConfiguration(double height)
    {
        if (sensor == null)
            return false;

        ISsiAntenna feature = (ISsiAntenna)sensor.getInterface(SsiInterfaceType.SsiAntenna);

        if (feature == null)
            return false;

        trimble.jssi.interfaces.gnss.antenna.IAntenna antenna = feature.getAntenna(AntennaType.Internal);

        if (antenna == null || antenna.getHeightConfiguration() == null)
        {
            return false;
        }

        try {
            AntennaHeightConfiguration heightConfiguration = new AntennaHeightConfiguration(height, MeasurementMethod.MeasurementMethod0);
            antenna.setAntennaHeightConfiguration(heightConfiguration);

            feature.setAntenna(antenna);
            return true;
        } catch (Exception e) {
            logException("UpdateAntennaHeightConfiguration", e);
        }
        return  false;
    }

    private final ISatelliteUpdateListener satelliteUpdateListener = satelliteUpdateEvent -> {
        List<ISatellite> satellites = satelliteUpdateEvent.getSatelliteUpdateContainer().getSatellites();
        int satellitesInView = 0;
        for (ISatellite sat : satellites) {
            if (sat.getEnabled() && sat.getSNR1() > 0.0)
                satellitesInView++;
        }
        triggerSatelliteUpdate(satellites, satellitesInView);
    };

    private final IConnectionStatusListener rtkConnectionStatusListener = ntripConnectionStatusChangedEvent -> triggerRtkConnectionStatusUpdate(ntripConnectionStatusChangedEvent.getStatus());

    private final ICurrentPowerSourceChangedListener powerUpdateListener = this::triggerPowerSourceUpdate;

    /**
     *  Get the current survey
     *
     *  @return the current survey
     */
    public  SurveyType getCurrentSurvey()
    {
        if(!isSensorConnected()) {
            return  SurveyType.None;
        }
        ISsiRTKSurvey ssiRtk = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);
        if(ssiRtk == null) {
            return SurveyType.None;
        }
        SurveySettings surveySettings = ssiRtk.getSurveySettings();
        if(surveySettings == null) {
            return SurveyType.None;
        }
        switch (surveySettings.getCorrectionDataSource().getType()) {
            case NTRIP:
                return SurveyType.RtkViaNtrip;
            case RTXViaIP:
                return SurveyType.RtxViaInternet;
            case RTXViaSatellite:
                return SurveyType.RtxViaSatellite;
            case TrimbleHub:
                return  SurveyType.TrimbleCorrectionHub;
        }
        return SurveyType.None;
    }

    /**
     * Get the sensor properties after successful connection
     * 
     * @return instrument name, serial number and firmware version
     */
    public ReturnObject<SensorProperties> getSensorProperties() {
        String instrumentName = "";
        String serialNumber = "";
        String firmware = "";
        boolean licensed = false;

        if (!isSensorConnected()) {
            return new ReturnObject<>(DriverReturnCode.ErrorNotConnected);
        }
        ISsiSensorProperties sensorProperties = (ISsiSensorProperties) sensor.getInterface(SsiInterfaceType.SsiSensorProperties);
        if (sensorProperties.getProperty(ISensorNameProperty.class) != null)
            instrumentName = sensorProperties.getProperty(ISensorNameProperty.class).getValue();

        if (sensorProperties.getProperty(ISensorSerialNumberProperty.class) != null)
            serialNumber = sensorProperties.getProperty(ISensorSerialNumberProperty.class).getValue();

        if (sensorProperties.getProperty(ISensorFirmwareProperty.class) != null)
            firmware = sensorProperties.getProperty(ISensorFirmwareProperty.class).getValue();

        if (sensorProperties.getProperty(ISensorLicensedProperty.class) != null)
            licensed = sensorProperties.getProperty(ISensorLicensedProperty.class).getValue();

        return new ReturnObject<>(DriverReturnCode.Success, new SensorProperties(instrumentName, serialNumber, firmware,licensed));
    }

    /**
     * get the antenna height from the bottom of the antenna
     * 
     * @return double Reduced Antenna Height
     */
    public double getReducedAntennaHeight() {
        return this.reducedAntennaHeight;
    }

    /**
     * set the antenna height from the bottom of the antenna
     *
     */
    public void setReducedAntennaHeight(double height) {
        if (updateAntennaHeightConfiguration(height)) {
            this.reducedAntennaHeight = height;
        }
    }
    
    /**
     * Is the static motion mode supported
     *
     * @return boolean true if Static Motion Mode is Supported
     */
    public boolean isStaticMotionModeSupported() {
    	ISsiPositioning ssiPositioning = (ISsiPositioning) sensor.getInterface(SsiInterfaceType.SsiPositioning);
        if (ssiPositioning != null && ssiPositioning.isSupported(PositioningParameterType.MotionState)) {
            IPositioningParameterMotionState motionState = (IPositioningParameterMotionState)ssiPositioning.createPositioningParameter(PositioningParameterType.MotionState);
            return motionState.listSupportedMotionStates().contains(MotionState.Static);
        }
        return false;
    }
    
    /**
     * Set receiver in static or roving mode
     * 
     * @param staticMode true to enable static motion mode
     * 
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode setMotionState(boolean staticMode) {
        if(!isSensorConnected()) {
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }
        
        ISsiPositioning ssiPositioning = (ISsiPositioning) sensor.getInterface(SsiInterfaceType.SsiPositioning);

        if (!ssiPositioning.isSupported(PositioningParameterType.MotionState)) {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }

        if (staticMode && !isStaticMotionModeSupported()) {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }

        PositioningSettings settings = new PositioningSettings();

        IPositioningParameterMotionState motionState = (IPositioningParameterMotionState) ssiPositioning.createPositioningParameter(PositioningParameterType.MotionState);
        motionState.setState(staticMode ? MotionState.Static : MotionState.Roving);
        settings.add(motionState);

        try {
            ssiPositioning.startPositioning(settings);
        } catch (Exception e) {
            logException("setMotionState", e);
            return new ReturnCode(DriverReturnCode.Error);
        }
        return new ReturnCode(DriverReturnCode.Success);
    }

    /**
     * Is Trimble Correction Hub supported?
     * 
     * @return boolean true if Trimble Correction Hub is Supported
     */
    public boolean isTrimbleCorrectionHubSupported() {
        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);
        return ssiRtkSurvey != null && ssiRtkSurvey.isSupported(LinkParameterType.ControllerInternet)
                && ssiRtkSurvey.isSupported(LinkParameterType.ControllerInternet, CorrectionDataSourceType.TrimbleHub);
    }

    /**
     * Start Trimble Correction Hub
     *
     * @param targetReferenceFrame Target Reference Frame
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode startTrimbleCorrectionHubSurvey(TargetReferenceFrame targetReferenceFrame) {
        return startTrimbleCorrectionHubSurvey(targetReferenceFrame, 0, "");
    }

    /**
     * Start Trimble Correction Hub
     * 
     * @param targetReferenceFrame Target Reference Frame
     * @param targetReferenceFrameId The target reference frame id
     * @param geoidGridFileFullPath The location of the grid file used to calculate geoid undulation and elevation
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode startTrimbleCorrectionHubSurvey(TargetReferenceFrame targetReferenceFrame, int targetReferenceFrameId, String geoidGridFileFullPath) {
        if (!isSensorConnected()) {
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }

        if (!isTrimbleCorrectionHubSupported()) {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }

        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);

        ILinkParameterControllerInternetSettings controllerInternet =
                (ILinkParameterControllerInternetSettings) ssiRtkSurvey.createLinkParameter(LinkParameterType.ControllerInternet);
        ICorrectionDataSourceTrimbleHubSettings cdsTrimbleHub =
                (ICorrectionDataSourceTrimbleHubSettings) ssiRtkSurvey.createCorrectionDataSource(CorrectionDataSourceType.TrimbleHub);

        cdsTrimbleHub.setUseLocalSettings(true);
        cdsTrimbleHub.setUseRTXFallBack(true);
		cdsTrimbleHub.setUseSurveyMonitor(true);

        resetParameter();
        IDatumTransformationTimeDependent timeDependentTransformation = createTimeDependentTransformation(ssiRtkSurvey, targetReferenceFrame, targetReferenceFrameId);

        rtkCorrectionHubStarted = true;
        restartCorrectionHub = () -> {
            ReturnCode result = endSurvey();
            if (result.getCode() != DriverReturnCode.Success)
            {
                logDebug("startTrimbleCorrectionHubSurvey",
                        "Failed to end survey in restartRtkCorrectionHub");
                return;
            }

            DriverReturnCode retCode = startSurvey(ssiRtkSurvey, controllerInternet, cdsTrimbleHub,
                    timeDependentTransformation, geoidGridFileFullPath, "startTrimbleCorrectionHubSurvey");
            if (retCode != DriverReturnCode.Success)
            {
                logDebug("startTrimbleCorrectionHubSurvey",
                        "Failed to start survey in restartRtkCorrectionHub");
            }
        };


        return new ReturnCode(startSurvey(ssiRtkSurvey, controllerInternet, cdsTrimbleHub,
                timeDependentTransformation, geoidGridFileFullPath, "StartTrimbleCorrectionHubSurvey"));
    }

    /**
     * Is Rtx via Internet supported?
     * 
     * @return boolean true if RTX Via Internet is Supported
     */
    public boolean isRtxViaInternetSupported() {
        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);
        return ssiRtkSurvey != null && ssiRtkSurvey.isSupported(LinkParameterType.ControllerInternet)
                && ssiRtkSurvey.isSupported(LinkParameterType.ControllerInternet, CorrectionDataSourceType.RTXViaIP);
    }

    /**
     * Start Rtx via Internet
     *
     * @param targetReferenceFrame Target Reference Frame
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode startRtxViaInternet(TargetReferenceFrame targetReferenceFrame) {
        return startRtxViaInternet(targetReferenceFrame, 0, "");
    }

    /**
     * Start Rtx via Internet
     * @param targetReferenceFrame Target Reference Frame
     * @param targetReferenceFrameId The target reference frame id
     * @param geoidGridFileFullPath The location of the grid file used to calculate geoid undulation and elevation
     *
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode startRtxViaInternet(TargetReferenceFrame targetReferenceFrame, int targetReferenceFrameId, String geoidGridFileFullPath) {
        if (!isSensorConnected()) {
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }

        if (!isRtxViaInternetSupported()) {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }

        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);

        ILinkParameterControllerInternetSettings controllerInternet =
                (ILinkParameterControllerInternetSettings) ssiRtkSurvey.createLinkParameter(LinkParameterType.ControllerInternet);
        ICorrectionDataSourceRTXViaIpSettings cdsRTXViaIP =
                (ICorrectionDataSourceRTXViaIpSettings) ssiRtkSurvey.createCorrectionDataSource(CorrectionDataSourceType.RTXViaIP);

        controllerInternet.setCommunicator(new trimble.jssi.drivercommon.connection.TCPCommunicator());
        cdsRTXViaIP.setStream(RTXDataStream.RTXAUTO);

        resetParameter();
        return new ReturnCode(startSurvey(ssiRtkSurvey, controllerInternet, cdsRTXViaIP,
                createTimeDependentTransformation(ssiRtkSurvey, targetReferenceFrame, targetReferenceFrameId), geoidGridFileFullPath, "StartRtxViaInternet"));
    }

    /**
     * Is Rtx via LBand supported?
     * 
     * @return boolean true if RTX Via Satellite is Supported
     */
    public boolean isRtxViaSatelliteSupported() {
        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);
        return ssiRtkSurvey != null && ssiRtkSurvey.isSupported(LinkParameterType.RTXSatellite)
                && ssiRtkSurvey.isSupported(LinkParameterType.RTXSatellite, CorrectionDataSourceType.RTXViaSatellite);
    }

    /**
     * Start Rtx via LBand
     *
     * @param targetReferenceFrame Target Reference Frame
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode startRtxViaSatellite(TargetReferenceFrame targetReferenceFrame) {
        return startRtxViaSatellite(targetReferenceFrame, 0, "");
    }

    /**
     * Start Rtx via LBand
     *
     * @param targetReferenceFrame Target Reference Frame
     * @param targetReferenceFrameId The target reference frame id
     * @param geoidGridFileFullPath The location of the grid file used to calculate geoid undulation and elevation
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode startRtxViaSatellite(TargetReferenceFrame targetReferenceFrame, int targetReferenceFrameId, String geoidGridFileFullPath) {
        if (!isSensorConnected()) {
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }

        if (!isRtxViaSatelliteSupported()) {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }

        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);

        ILinkParameterRTXSatelliteSettings rtxSatellite =
                (ILinkParameterRTXSatelliteSettings) ssiRtkSurvey.createLinkParameter(LinkParameterType.RTXSatellite);
        ICorrectionDataSourceRTXSettings cdsRTXViaSatellite =
                (ICorrectionDataSourceRTXSettings) ssiRtkSurvey.createCorrectionDataSource(CorrectionDataSourceType.RTXViaSatellite);

        resetParameter();
        return new ReturnCode(startSurvey(ssiRtkSurvey, rtxSatellite, cdsRTXViaSatellite,
                createTimeDependentTransformation(ssiRtkSurvey, targetReferenceFrame, targetReferenceFrameId), geoidGridFileFullPath, "StartRtxViaSatellite"));
    }

    /*
     * Is RTK via Ntrip supported?
     */
    public boolean isRtkViaNtripSupported() {

        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);
        return ssiRtkSurvey != null && ssiRtkSurvey.isSupported(LinkParameterType.ControllerInternet)
                && ssiRtkSurvey.isSupported(LinkParameterType.ControllerInternet, CorrectionDataSourceType.NTRIP);
    }


    /**
     * Download Ntrip source table
     *
     * @param serverAddress Server Address
     * @param port Port
     * @return ReturnObject Source Table List
     */
    public ReturnObject<Collection<String>> getNtripSourceTable(String serverAddress, int port) {
        return getNtripSourceTable(serverAddress, port, "", "");
    }


    /**
     * Download Ntrip source table
     * 
     * @param serverAddress Server Address
     * @param port Port
     * @param username User Name, in case of IBSS needed
     * @param password Password, in case of IBSS needed
     * @return ReturnObject Source Table List
     */
    public ReturnObject<Collection<String>> getNtripSourceTable(String serverAddress, int port, String username, String password) {

        final Collection<String> sourceTable = new ArrayList<>();

        if (!isSensorConnected()) {
            return new ReturnObject<>(DriverReturnCode.ErrorNotConnected);
        }

        if (!isRtkViaNtripSupported()) {
            return new ReturnObject<>(DriverReturnCode.ErrorFunctionNotSupported);
        }

        final ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);


        if(ssiRtkSurvey.getSurveyState() != SurveyState.NotRunning) {
            return new ReturnObject<>(DriverReturnCode.ErrorFunctionNotSupported);
        }

        ILinkParameterControllerInternetSettings controllerInternet =
                (ILinkParameterControllerInternetSettings) ssiRtkSurvey.createLinkParameter(LinkParameterType.ControllerInternet);
        ICorrectionDataSourceNTRIPSettings correctionDataSourceNtrip =
                (ICorrectionDataSourceNTRIPSettings) ssiRtkSurvey.createCorrectionDataSource(CorrectionDataSourceType.NTRIP);
        
        controllerInternet.setCommunicator(new trimble.jssi.drivercommon.connection.TCPCommunicator());
        correctionDataSourceNtrip.setServerAddress(serverAddress);
        correctionDataSourceNtrip.setServerPort(port);
        correctionDataSourceNtrip.setUser(username);
        correctionDataSourceNtrip.setPassword(password);
        final Object waitForMountPoint = new Object();

        correctionDataSourceNtrip.setMountPointRequestCallback(mountPointRequestedEvent -> {
            Collection<String[]> mountPointStrings = mountPointRequestedEvent.getMountPointList();
            ArrayList<String> mountPoints = new ArrayList<>();
            for (String[] mountPointString : mountPointStrings) {
                StringBuilder mountPointStringBuilder = new StringBuilder();
                for (String mountPointPart : mountPointString) {
                    mountPointStringBuilder.append(mountPointPart);
                    mountPointStringBuilder.append(";");
                }
                mountPoints.add(mountPointStringBuilder.toString());
            }

            sourceTable.addAll(mountPoints);

            try {
                ssiRtkSurvey.cancelStartSurvey();
            } catch (Exception ex) {
                try {
                    ssiRtkSurvey.endSurvey();
                } catch (Exception exIn) {
                    //Ignoring exceptions
                }
            }
            return null;
        });
        DriverReturnCode returnCode = DriverReturnCode.Success;

        ssiRtkSurvey.beginStartSurvey(new SurveySettings(controllerInternet, correctionDataSourceNtrip,
                        null, null, null), asyncResult -> {
                            try {
                                asyncResult.getResult();
                            } catch (Exception e) {
                                if (sourceTable.isEmpty()) {
                                    logException("GetMountpoints", e);
                                }
                            }
                            synchronized (waitForMountPoint) {
                                waitForMountPoint.notifyAll();
                            }
                        });
        synchronized (waitForMountPoint) {
            try {
                waitForMountPoint.wait();
            } catch (InterruptedException e) {
                //Ignoring exceptions
            }
        }
        if (!sourceTable.isEmpty()) {
            return new ReturnObject<>(returnCode, sourceTable);
        } else {
            if(ssiRtkSurvey.getSurveyState() == SurveyState.Running) {
                endSurvey();
            }
            returnCode = DriverReturnCode.ErrorGetSourceTable;
        }
        return new ReturnObject<>(returnCode);
    }

    /**
     * Start RTK via NTRIP
     *
     * @param serverAddress Server Address
     * @param port Port
     * @param user User Name
     * @param password Password
     * @param mountPoint Mount Point
     * @param targetReferenceFrame Target Reference Frame
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode startRTKViaNtrip(String serverAddress, int port, String user, String password, String mountPoint, TargetReferenceFrame targetReferenceFrame) {
        return startRTKViaNtrip(serverAddress, port, user, password, mountPoint, targetReferenceFrame, "");
    }

        /**
         * Start RTK via NTRIP
         *
         * @param serverAddress Server Address
         * @param port Port
         * @param user User Name
         * @param password Password
         * @param mountPoint Mount Point
         * @param targetReferenceFrame Target Reference Frame
         * @param geoidGridFileFullPath The location of the grid file used to calculate geoid undulation and elevation
         * @return ReturnCode The driver return code @see DriverReturnCode
         */
    public ReturnCode startRTKViaNtrip(String serverAddress, int port, String user, String password, String mountPoint,
                                       TargetReferenceFrame targetReferenceFrame, String geoidGridFileFullPath) {
        if (!isSensorConnected()) {
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }

        if (!isRtkViaNtripSupported()) {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }

        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);

        ILinkParameterControllerInternetSettings controllerInternet =
                (ILinkParameterControllerInternetSettings) ssiRtkSurvey.createLinkParameter(LinkParameterType.ControllerInternet);
        ICorrectionDataSourceNTRIPSettings cdsNtrip =
                (ICorrectionDataSourceNTRIPSettings) ssiRtkSurvey.createCorrectionDataSource(CorrectionDataSourceType.NTRIP);

        controllerInternet.setCommunicator(new trimble.jssi.drivercommon.connection.TCPCommunicator());
        cdsNtrip.setDataFormat(CorrectionDataFormat.RTCMVRS);
        cdsNtrip.setServerAddress(serverAddress);
        cdsNtrip.setServerPort(port);
        cdsNtrip.setUser(user);
        cdsNtrip.setPassword(password);
        cdsNtrip.setMountPoint(mountPoint);

        resetParameter();

        return new ReturnCode(startSurvey(ssiRtkSurvey, controllerInternet, cdsNtrip,
                createTimeDependentTransformation(ssiRtkSurvey, targetReferenceFrame, 0), geoidGridFileFullPath, "StartRTKViaNtrip"));
    }

    /**
     * Is RTK via direct internet connection supported?
     * 
     * @return boolean true if RTK Via DirectIP is Supported
     */
    public boolean isRTKViaDirectIpSupported() {
        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);
        return ssiRtkSurvey != null && ssiRtkSurvey.isSupported(LinkParameterType.ControllerInternet)
                && ssiRtkSurvey.isSupported(LinkParameterType.ControllerInternet, CorrectionDataSourceType.DirectIP);
    }

    /**
     /**
     * Start RTK via direct Internet connection
     *
     * @param serverAddress Server Address
     * @param port Port
     * @param targetReferenceFrame Target Reference Frame
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode startRTKViaDirectIp(String serverAddress, int port, TargetReferenceFrame targetReferenceFrame) {
        return startRTKViaDirectIp(serverAddress, port, targetReferenceFrame, "");
    }

    /**
    /**
     * Start RTK via direct Internet connection
     *
     * @param serverAddress Server Address
     * @param port Port
     * @param targetReferenceFrame Target Reference Frame
     * @param geoidGridFileFullPath The location of the grid file used to calculate geoid undulation and elevation
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode startRTKViaDirectIp(String serverAddress, int port, TargetReferenceFrame targetReferenceFrame, String geoidGridFileFullPath) {
        if (!isSensorConnected()) {
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }

        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);
        if (!isRTKViaDirectIpSupported()) {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }

        ILinkParameterControllerInternetSettings controllerInternet =
                (ILinkParameterControllerInternetSettings) ssiRtkSurvey.createLinkParameter(LinkParameterType.ControllerInternet);
        ICorrectionDataSourceDirectIPSettings cdsDirectIp =
                (ICorrectionDataSourceDirectIPSettings) ssiRtkSurvey.createCorrectionDataSource(CorrectionDataSourceType.DirectIP);

        cdsDirectIp.setDataFormat(CorrectionDataFormat.RTCM32);

        cdsDirectIp.setServerAddress(serverAddress);
        cdsDirectIp.setServerPort(port);

        resetParameter();
        return new ReturnCode(startSurvey(ssiRtkSurvey, controllerInternet, cdsDirectIp,
                createTimeDependentTransformation(ssiRtkSurvey, targetReferenceFrame, 0), geoidGridFileFullPath, "StartRTKViaDirectIp"));
    }

    /**
     * End current survey
     * 
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode endSurvey() {
        rtkCorrectionHubStarted = false;
        if (!isSensorConnected()) {
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }
        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);
        DriverReturnCode returnCode = DriverReturnCode.Success;

        if (ssiRtkSurvey == null) {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }

        resetParameter();
        try {
            if (ssiRtkSurvey.getSurveySettings() != null) {
                ICorrectionDataSource correctionDataSource = ssiRtkSurvey.getSurveySettings().getCorrectionDataSource();
                if (correctionDataSource != null) {
                    if (correctionDataSource instanceof ICorrectionDataSourceTCPSettings) {
                        ICorrectionDataSourceTCPSettings correctionDataSourceTCPSettings = (ICorrectionDataSourceTCPSettings) correctionDataSource;
                        correctionDataSourceTCPSettings.removeConnectionsStatusListener(rtkConnectionStatusListener);
                    }
                    if (correctionDataSource instanceof ICorrectionDataSourceTrimbleHubSettings) {
                        ICorrectionDataSourceTrimbleHubSettings correctionDataSourceTrimbleHubSettings = (ICorrectionDataSourceTrimbleHubSettings) correctionDataSource;
                        correctionDataSourceTrimbleHubSettings.removeConnectionsStatusListener(rtkConnectionStatusListener);
                    }
                }
            }

            ssiRtkSurvey.endSurvey();
        } catch (Exception e) {
            returnCode = DriverReturnCode.Error;
            logException("EndSurvey", e);
        }
        return new ReturnCode(returnCode);
    }

   /**
     * Is Datum Transformation supported?
     * @return Returns true if the Datum Transformation is supported
     */
    public boolean isDatumTransformationSupported()
    {
        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);
        return (ssiRtkSurvey != null);
    }

    /**
     * Is Vertical Adjustment supported?
     * @return Returns true if the Vertical Adjustment is supported
     */
    public boolean isVerticalAdjustmentSupported()
    {
        ISsiRTKSurvey ssiRtkSurvey = (ISsiRTKSurvey) sensor.getInterface(SsiInterfaceType.SsiRTKSurvey);
        return (ssiRtkSurvey != null);
    }


    private IDatumTransformationTimeDependent createTimeDependentTransformation(ISsiRTKSurvey ssiRtkSurvey, TargetReferenceFrame targetReferenceFrame, int targetReferenceFrameId)
    {
        if(!isDatumTransformationSupported()) {
            return null;
        }
        IDatumTransformationTimeDependent datumTransformation = ssiRtkSurvey.createDatumTransformation(trimbleIdLoginSuccessful);

        if(datumTransformation == null)
            return  null;

        // Source is via default AutoSelection

        switch (targetReferenceFrame) {

            case ToGlobal:
                // ReferenceFrameID  ITRF2020
                datumTransformation.getTargetFrame().setReferenceFrameID(305);
                datumTransformation.getTargetFrame().setReferenceFrameEpoch(ReferenceFrameEpoch.Current);
                break;
            case ToLocal:
                datumTransformation.getTargetFrame().setReferenceFrameID(0);
                datumTransformation.getTargetFrame().setReferenceFrameEpoch(ReferenceFrameEpoch.Reference);
                break;
            case ToFixGlobal:
                datumTransformation.getTargetFrame().setReferenceFrameID(305);
                datumTransformation.getTargetFrame().setReferenceFrameEpoch(ReferenceFrameEpoch.Reference);
                break;
            case UseLocalSettings:
                break;
            case ToLocalWithTargetReferenceFrame:
                // Source is not activated
                datumTransformation.getSourceFrame().setReferenceFrameID(-2);
                datumTransformation.getSourceFrame().setReferenceFrameEpoch(ReferenceFrameEpoch.Reference);
                // Target from user
                datumTransformation.getTargetFrame().setReferenceFrameID(targetReferenceFrameId);
                datumTransformation.getTargetFrame().setReferenceFrameEpoch(ReferenceFrameEpoch.Reference);
                break;
            default:
                datumTransformation = null;
                break;

        }
        return datumTransformation;
    }

    private IVerticalAdjustment createVerticalAdjustment(ISsiRTKSurvey ssiRTKSurvey, String geoidGridFileFullPath)
    {
        if(!isVerticalAdjustmentSupported()) {
            return null;
        }

        IVerticalAdjustment verticalAdjustment = ssiRTKSurvey.createVerticalAdjustment(trimbleIdLoginSuccessful);
        
        if(verticalAdjustment == null)
            return  null;


        if (!geoidGridFileFullPath.isEmpty()) {
            verticalAdjustment.setGeoidGridFileFullPath(geoidGridFileFullPath);
        }

        return verticalAdjustment;
    }

    protected void triggerPositionUpdate(SolutionType solutionType, double l, double g, double h, GroundPositionType groundPositionType, double heading, double horizontalVelocity,
                                         double verticalVelocity, double haPrec, double vaPrec, double sigmaSemiMajorAxis, double sigmaSemiMinorAxis, double sigmaOrientation,
                                         ImuState inertialMeasurementUnitState, double pitch, double roll, double yaw, double pitchPrecision, double rollPrecision, double yawPrecision,
                                         double pdop, double hdop, double vdop, Date gpsTime, Date utcTime, int numberOfSatellites, int numberTrackedSatellites,
                                         int staticEpochs, double age, long receivedCorrectionData, int stationId,
										 boolean datumTransformationApplied, IReferenceSystem sourceFrame, IReferenceSystem targetFrame,
										 double elevation, String geoidModel) {

        synchronized (catalystEventListeners) {
            for (ICatalystEventListener catalystEventListener : catalystEventListeners) {
                catalystEventListener.onPositionUpdate(new PositionUpdate(this, solutionType, l, g, h, groundPositionType, heading, horizontalVelocity,
                        verticalVelocity, haPrec, vaPrec, sigmaSemiMajorAxis, sigmaSemiMinorAxis, sigmaOrientation, inertialMeasurementUnitState, pitch, roll, yaw,
                        pitchPrecision, rollPrecision, yawPrecision, pdop, hdop, vdop, gpsTime, utcTime,
                        numberOfSatellites, numberTrackedSatellites, staticEpochs, age, receivedCorrectionData, stationId,
                        datumTransformationApplied, sourceFrame, targetFrame,  elevation, geoidModel));
            }
        }

    }

    protected void triggerSensorOutsideGeofence() {
        synchronized (catalystEventListeners) {
            for (ICatalystEventListener catalystEventListener : catalystEventListeners) {
                catalystEventListener.onSensorOutsideGeofence();
            }
        }
    }

    protected void triggerRtkServiceAvailable() {
        synchronized (catalystEventListeners) {
            for (ICatalystEventListener catalystEventListener : catalystEventListeners) {
                catalystEventListener.onRtkServiceAvailable();
            }
        }
    }

    protected void triggerSurveyTypeUpdate(SurveyType surveyType) {
        synchronized (catalystEventListeners) {
            for (ICatalystEventListener catalystEventListener : catalystEventListeners) {
                catalystEventListener.onSurveyTypeUpdate(surveyType);
            }
        }
    }

    protected void triggerRtxServiceAvailable() {
        synchronized (catalystEventListeners) {
            for (ICatalystEventListener catalystEventListener : catalystEventListeners) {
                catalystEventListener.onRtxServiceAvailable();
            }
        }
    }

    protected void triggerSatelliteUpdate(List<ISatellite> satellites, int satellitesInView) {
        synchronized (catalystEventListeners) {
            for (ICatalystEventListener catalystEventListener : catalystEventListeners) {
                catalystEventListener.onSatelliteUpdate(new SatelliteUpdate(this, satellites), satellitesInView);
            }
        }
    }


    final MappingTable<GPRSNTRIPStartStatus, RtkConnectionStatus> rtkConnectionStatusMap =
            new MappingTable<GPRSNTRIPStartStatus, RtkConnectionStatus>() {

                @Override
                public void fill() {
                    /*
                     * First few intermediate states in NTRIP connection are
                     * mapped to StartingNTRIP
                     */
                    add(GPRSNTRIPStartStatus.Prepare, RtkConnectionStatus.StartingNTRIP);
                    add(GPRSNTRIPStartStatus.CheckPPP, RtkConnectionStatus.StartingNTRIP);
                    add(GPRSNTRIPStartStatus.InitPPP, RtkConnectionStatus.StartingNTRIP);
                    add(GPRSNTRIPStartStatus.CheckPin, RtkConnectionStatus.StartingNTRIP);
                    add(GPRSNTRIPStartStatus.StartPPP, RtkConnectionStatus.StartingNTRIP);
                    add(GPRSNTRIPStartStatus.PPPRunning, RtkConnectionStatus.StartingNTRIP);
                    add(GPRSNTRIPStartStatus.StartNTRIP, RtkConnectionStatus.StartingNTRIP);
                    add(GPRSNTRIPStartStatus.GetSourceTable, RtkConnectionStatus.StartingNTRIP);
                    add(GPRSNTRIPStartStatus.GetSourceTableCount, RtkConnectionStatus.StartingNTRIP);

                    add(GPRSNTRIPStartStatus.WaitCorrectiondata, RtkConnectionStatus.WaitingCorrectiondata);
                    add(GPRSNTRIPStartStatus.NTRIPRunning, RtkConnectionStatus.NTRIPRunning);
                    add(GPRSNTRIPStartStatus.StartRTXViaIP, RtkConnectionStatus.StartingRTXViaIP);
                    add(GPRSNTRIPStartStatus.RTXViaIPRunning, RtkConnectionStatus.RTXViaIPRunning);
                    add(GPRSNTRIPStartStatus.StartRTXViaSatellite, RtkConnectionStatus.StartingRTXViaSatellite);
                    add(GPRSNTRIPStartStatus.RTXViaSatelliteRunning, RtkConnectionStatus.RTXViaSatelliteRunning);
                }
            };

    protected void triggerRtkConnectionStatusUpdate(GPRSNTRIPStartStatus gprsntripStartStatus) {
        synchronized (catalystEventListeners) {
            for (ICatalystEventListener catalystEventListener : catalystEventListeners) {
                catalystEventListener.onRtkConnectionStatusUpdate(rtkConnectionStatusMap.mapKeyToValue(gprsntripStartStatus));
            }
        }
    }

    protected void triggerStateChanged(ConnectionState connectionState) {

        synchronized (catalystEventListeners) {
            for (ICatalystEventListener catalystEventListener : catalystEventListeners) {
                catalystEventListener.onSensorStateChanged(new SensorStateEvent(this, connectionState));
            }
        }
    }

    protected void triggerUsbConnectionErrorOccured() {
        synchronized (catalystEventListeners) {
            for (ICatalystEventListener catalystEventListener : catalystEventListeners) {
                catalystEventListener.onUsbConnectionErrorOccured();
            }
        }
    }

    protected void triggerSubscriptionHasExpired() {

        synchronized (catalystEventListeners) {
            for (ICatalystEventListener catalystEventListener : catalystEventListeners) {
                catalystEventListener.onSubscriptionHasExpired();
            }
        }
    }

    protected void triggerPowerSourceUpdate(PowerSourceEvent powerSourceEvent) {

        // Convert to a PowerSourceState
        //
        PowerSourceState state = null;

        IPowerSource powerSource = powerSourceEvent.getPowerSource();
        if (powerSource != null) {
            if (powerSource.getPowerSourceType() == ExternalPower) {
                state = new PowerSourceState(-1, true);
            }
            if (powerSource.getPowerSourceType() == InternalBattery) {
                IBattery battery = (IBattery)powerSource;
                state = new PowerSourceState(battery.getLevel(), false);
            }
        }

        if (state != null) {
            synchronized (catalystEventListeners) {
                for (ICatalystEventListener catalystEventListener : catalystEventListeners) {
                    catalystEventListener.onPowerUpdate(state);
                }
            }
        }
    }

    private DriverReturnCode startSurvey(ISsiRTKSurvey ssiRtkSurvey, ILinkParameter linkParameter, ICorrectionDataSource correctionDataSource,
                                         IDatumTransformationTimeDependent datumTransformation, String geoidGridFileFullPath, String surveyTypeString) {
        DriverReturnCode returnCode = DriverReturnCode.Success;
        try {
            IVerticalAdjustment verticalAdjustment = createVerticalAdjustment(ssiRtkSurvey, geoidGridFileFullPath);

            if (correctionDataSource != null) {
                if (correctionDataSource instanceof ICorrectionDataSourceTCPSettings) {
                    ICorrectionDataSourceTCPSettings correctionDataSourceTCPSettings = (ICorrectionDataSourceTCPSettings) correctionDataSource;
                    correctionDataSourceTCPSettings.addConnectionsStatusListener(rtkConnectionStatusListener);
                }
                if (correctionDataSource instanceof ICorrectionDataSourceTrimbleHubSettings) {
                    ICorrectionDataSourceTrimbleHubSettings correctionDataSourceTrimbleHubSettings = (ICorrectionDataSourceTrimbleHubSettings) correctionDataSource;
                    correctionDataSourceTrimbleHubSettings.addConnectionsStatusListener(rtkConnectionStatusListener);
                }
            }
            ssiRtkSurvey.startSurvey(new SurveySettings(linkParameter, correctionDataSource, new ArrayList<>(), datumTransformation, verticalAdjustment));
        } catch (NTRIPSetupException e) {
            returnCode = DriverReturnCode.ErrorStartNTRIP;
            logException(surveyTypeString, e);
            if (e.hasSetupFailed()) {
                returnCode = DriverReturnCode.ErrorSetupNTRIP;
            }
            if (e.isAuthorizationFaulty()) {
                returnCode = DriverReturnCode.ErrorNtripLoginFailed;
            }
            if (e.isMountpointWrong()) {
                returnCode = DriverReturnCode.ErrorWrongMountPoint;
            }
            if (e.isRoverOutsideVRSNetwork()) {
                returnCode = DriverReturnCode.ErrorRoverOutsideVRSNetwork;
            }
        } catch (IllegalArgumentException e) {
            returnCode = DriverReturnCode.ErrorInvalidParameter;
            logException(surveyTypeString, e);
        } catch (InternetNotConnectedException e) {
            returnCode = DriverReturnCode.ErrorInternetNotConnected;
            logException(surveyTypeString, e);
        } catch (Exception e) {
            returnCode = DriverReturnCode.Error;
            logException(surveyTypeString, e);
        }

        return returnCode;
    }


    /**
     * Returns battery level of external receiver in percent
     *
     * @return 0 = unknown
     */
    public int getExternalReceiverBatteryLevel()
    {
        try
        {
            if (!this.isSensorConnected())
            {
                return 0;
            }

            ISsiPower power = (ISsiPower)sensor.getInterface(SsiInterfaceType.SsiPower);
            IBattery battery = (IBattery)power.getCurrentPowerSource();

            if (battery == null)
                return 0;

            return battery.getLevel();
        }
        catch(Exception e)
        {
            return 0;
        }
    }


    /**
     * Installs a subscription
     *
     * @return  errorCode
     */
    public ReturnCode installSubscriptions(String optionCode)
    {
        if (currentDeviceType == DriverType.Catalyst)
        {
            return new ReturnCode(DriverReturnCode.DeviceTypeNotSupported);
        }

        if (!isSensorConnected())
        {
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }

        ISsiOptionCode i = (ISsiOptionCode)sensor.getInterface(SsiInterfaceType.SsiOptionCode);

        try {
            i.installOptionCode(optionCode);
        }
        catch (SsiException e){
            return new ReturnCode(DriverReturnCode.ErrorInvalidParameter);
        }

        return new ReturnCode(DriverReturnCode.Success);
    }

    /**
     *
     * @param subscriptionListContainer Subscription List
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode getRTXSubscriptions(List<SubscriptionType> subscriptionListContainer)
    {
        if (subscriptionListContainer == null)
        {
            throw new IllegalArgumentException("Parameter subscriptionListContainer must be initialized before passing it");
        }

        if (currentDeviceType == DriverType.Catalyst)
        {
            return new ReturnCode(DriverReturnCode.DeviceTypeNotSupported);
        }

        if (!isSensorConnected())
        {
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }

        if (!sensor.isInterfaceTypeSupported(SsiInterfaceType.SsiOptionCode))
        {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }

        ISsiSubscriptions i = (ISsiSubscriptions)sensor.getInterface(SsiInterfaceType.SsiSubscriptions);

        buildSubscriptionList(i, subscriptionListContainer, SubscriptionTypeGnss.RTX);
        buildSubscriptionList(i, subscriptionListContainer, SubscriptionTypeGnss.RTXFast);
        buildSubscriptionList(i, subscriptionListContainer, SubscriptionTypeGnss.FieldPointRTX);
        buildSubscriptionList(i, subscriptionListContainer, SubscriptionTypeGnss.RangePointRTX);
        buildSubscriptionList(i, subscriptionListContainer, SubscriptionTypeGnss.ViewPointRTX);

        return new ReturnCode(DriverReturnCode.Success);
    }

    private void buildSubscriptionList(ISsiSubscriptions i, List<SubscriptionType> container, SubscriptionType subscriptionType)
    {
        if (i.hasSubscription(subscriptionType))
        {
            container.add(i.getSubscription(subscriptionType).getSubscriptionType());
        }
    }

    /**
     * Sets position rate to the specified value
     * @param positionRate Output Rate for position
     * @return ReturnCode The driver return code @see DriverReturnCode
     */
    public ReturnCode setOutputPositionRate(PositionRate positionRate)
    {
        if (!isSensorConnected())
        {
            return new ReturnCode(DriverReturnCode.ErrorNotConnected);
        }

        if (!sensor.isInterfaceTypeSupported(SsiInterfaceType.SsiPositioning))
        {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }

        ISsiPositioning i = (ISsiPositioning) sensor.getInterface(SsiInterfaceType.SsiPositioning);

        if (!i.isSupported(PositioningParameterType.PositionRate))
        {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }

        IPositioningParameterRate positioningParameter = (IPositioningParameterRate) i.createPositioningParameter(PositioningParameterType.PositionRate);
        Collection<PositionRate> positionRates = positioningParameter.listSupportedPositionRates();
        if (!positionRates.contains(positionRate))
        {
            return new ReturnCode(DriverReturnCode.ErrorFunctionNotSupported);
        }
        positioningParameter.setRate(positionRate);
        ArrayList<IPositioningParameter> parameters = new ArrayList<>();
        parameters.add(positioningParameter);
        PositioningSettings positioningSettings = new PositioningSettings(parameters);

        try
        {
            i.startPositioning(positioningSettings);
        }
        catch (Exception e)
        {
            logException("SetOutputPositionRate",e);
            return new ReturnCode(DriverReturnCode.Error);
        }

        return new ReturnCode(DriverReturnCode.Success);
    }

    public boolean isDriverLogsEnabled() {
        File file = new File(driverManager.getDriverLogFilePath());
        return file.exists();
    }

    public void enableDriverLogs(boolean isChecked) {
        File logDirectory = new File(driverManager.getDriverLogFilePath());
        if(isChecked && !logDirectory.exists()) {
            logDirectory.mkdirs();
        } else if(!isChecked && logDirectory.exists()) {
            deleteDirectory(logDirectory);
        }
    }

    private boolean deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return directoryToBeDeleted.delete();
    }

    public String getDriverLogFilePath() {
        return driverManager.getDriverLogFilePath();
    }



    /**
     * Get receiver name
     * @param driverType Type of driver
     * @param bluetoothName Bluetooth name of the device
     * @return receiver name
     */
    public String getReceiverName(DriverType driverType, String bluetoothName)
    {
        switch (driverType)
        {
            case Catalyst:
                return "DA1";
            case TrimbleGNSS:
                if(bluetoothName== null || bluetoothName.trim().isEmpty())
                {
                    break;
                }
                return bluetoothName;
            case Mock:
                return "MOCK";
            case SpectraPrecision:
                return "BT";
            case EM100:
                return "EM100";
            case TDC150:
                return "TDC";
        }
        return "EMPTY";
    }
}