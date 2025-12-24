package trimble.jssi.android.catalystfacade;

/**
 * These values can be returned by the driver itself. They contain error
 * information about the failed call.
 */
public enum DriverReturnCode {
    /**
     * Success
     */
    Success,

    /**
     * Generic error
     */
    Error,

    /**
     * License error, no license.
     */
    ErrorNoLicense,

    /**
     * Unable to load the driver
     */
    ErrorLoadingDriver,

    /**
     * Sensor is already used from another application
     * Foreign application needs to release first connection
     */
    ErrorSensorAlreadyInUse,

    /**
     * Instrument already connected
     */
    ErrorAlreadyConnected,

    /**
     * No instrument connected
     */
    ErrorNotConnected,

    /**
     * Process canceled by user
     */
    ErrorCancelledFromUser,

    /**
     * Driver busy, another command is currently executed
     */
    ErrorDriverBusy,

    /**
     * Timeout
     */
    ErrorFunctionTimeout,

    /**
     * Invalid parameters
     */
    ErrorInvalidParameter,

    /**
     * Function not supported by the instrument
     */
    ErrorFunctionNotSupported,

    /**
     * Internet connection not available
     */
    ErrorInternetNotConnected,

    /**
     * NTRIP setup is invalid or not accepted by the server
     */
    ErrorSetupNTRIP,

    /**
     * NTRIP client could not get started
     */
    ErrorStartNTRIP,

    /**
     * Unable to download sourcetable from caster
     */
    ErrorGetSourceTable,

    /**
     * Provided NTRIP username or password wrong
     */
    ErrorNtripLoginFailed,

    /**
     * Mount point does not exists
     */
    ErrorWrongMountPoint,

    /**
     * The error rover is outside VRS network
     */
    ErrorRoverOutsideVRSNetwork,

    /**
     * The device type is not supported
     */
    DeviceTypeNotSupported
}
