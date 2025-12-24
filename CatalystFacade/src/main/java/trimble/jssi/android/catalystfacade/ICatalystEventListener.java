package trimble.jssi.android.catalystfacade;

public interface ICatalystEventListener {
    
    /**
     * Triggered when sensor connection state changes.
     * @param sensorStateEvent Sensor State Event
     */
    void onSensorStateChanged(SensorStateEvent sensorStateEvent);
    /**
     * Triggered on USB Connection error.
     */
    void onUsbConnectionErrorOccured();    
    /**
     * Triggered when user subscription has expired.
     */
    void onSubscriptionHasExpired();
    /**
     * Triggered on new position update
     * @param positionUpdate Position Update
     */
    void onPositionUpdate(PositionUpdate positionUpdate);
    /**
     * Triggered on new SV information.
     * @param satelliteUpdate Satellite Update Container
     * @param satellitesInView Number of Satellites in View
     */
    void onSatelliteUpdate(SatelliteUpdate satelliteUpdate, int satellitesInView);
    
    /**
     * Triggered when RTK service might be available.
     */
    void onRtkServiceAvailable();

    /**
     * Triggered when RTX service might be available.
     */    
    void onRtxServiceAvailable();
    
    
    /**
     * Triggered when RTK Connection status changes.
     * @param rtkConnectionStatus Rtk Connection Status
     */
    void onRtkConnectionStatusUpdate(RtkConnectionStatus rtkConnectionStatus);

    /**
     * Triggered when Survey Type changes.
     * @param surveyType  Survey Type
     */
    void onSurveyTypeUpdate(SurveyType surveyType);

    /**
     * Triggered when the sensor is outside supported area
     */
    void onSensorOutsideGeofence();

    /**
     * Triggered when IMU state changes
     */
    void onImuStateChanged(ImuStateEvent imuStateEvent);

    /**
     * Triggered when power source state changes
     */
    void onPowerUpdate(PowerSourceState powerSourceState);
}
