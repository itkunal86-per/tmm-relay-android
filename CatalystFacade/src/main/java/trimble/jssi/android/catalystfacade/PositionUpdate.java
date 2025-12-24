package trimble.jssi.android.catalystfacade;

import java.util.Date;
import java.util.EventObject;

import trimble.jssi.interfaces.gnss.positioning.IReferenceSystem;
import trimble.jssi.interfaces.gnss.positioning.SolutionType;
import trimble.jssi.interfaces.gnss.positioning.GroundPositionType;


public class PositionUpdate extends EventObject {
    private static final long serialVersionUID = 1L;
    
    /**
     * Get the solution type
     * @return solution type
     */
    public SolutionType getSolution() {
        return solution;
    }

    /**
     * Get the latitude
     * @return latitude in radian
     */
    public double getLatitude() {
        return latitude;
    }

    /**
     * Get the longitude
     * @return longitude in radian
     */
    public double getLongitude() {
        return longitude;
    }

    /**
     * Get the height 
     * @return height in meter including reduced antenna height
     */
    public double getHeight() {
        return height;
    }


    /**
     * Get the ground position type
     * @return ground position type.
     */
    public GroundPositionType getGroundPositionType() {
        return groundPositionType;
    }

    /**
     * Get the heading
     * @return heading is the WGS84 referenced true north heading in radian.
     */
    public double getHeading() {
        return heading;
    }

    /**
     * Get the horizontal velocity
     * @return horizontal velocity in meters per second.
     */
    public double getHorizontalVelocity() {
        return horizontalVelocity;
    }

    /**
     * Get the vertical velocity
     * @return vertical velocity in meters per second.
     */    
    public double getVerticalVelocity() {
        return verticalVelocity;
    }

    /**
     * Get the horizontal precision
     * @return horizontal precision in meter
     */
    public double getHPrecision() {
        return hPrecision;
    }

    /**
     * Get the vertical precision
     * @return vertical precision in meter
     */
    public double getVPrecision() {
        return vPrecision;
    }

    /**
     * Get the semi major axis
     * @return The error ellipse semi-major axis sigma error in meter
     */
    public double getSigmaSemiMajorAxis() {
        return sigmaSemiMajorAxis;
    }

    /**
     * Get the semi minor axis
     * @return The error ellipse semi-minor axis sigma error in meter
     */
    public double getSigmaSemiMinorAxis() {
        return sigmaSemiMinorAxis;
    }

    /**
     * Get the error ellipse orientation
     * @return The error ellipse orientation in radian
     */
    public double getSigmaOrientation() {
        return sigmaOrientation;
    }

    /**
     * Get the inertial measurement unit state
     * @return IMU state
     */
    public ImuState getInertialMeasurementUnitState () {  return inertialMeasurementUnitState; }

    /**
     * Get the pitch
     * @return pitch in radian
     */
    public double getPitch () { return pitch; }

    /**
     * Get the roll
     * @return roll in radian
     */
    public double getRoll () { return roll; }

    /**
     * Get the yaw
     * @return yaw in radian
     */
    public double getYaw () { return yaw; }

    /**
     * Get the pitch precision
     * @return The pitch standard deviation in radian.
     */
    public double getPitchPrecision () { return pitchPrecision; }

    /**
     * Get the roll precision
     * @return The roll standard deviation in radian.
     */
    public double getRollPrecision () { return rollPrecision; }

    /**
     * Get the yaw precision
     * @return The yaw standard deviation in radian.
     */
    public double getYawPrecision () { return yawPrecision; }

    /**
     * Get the position dilution of precision.
     * @return pdop
     */
    public double getPdop() {
        return pdop;
    }

    /**
     * Get the horizontal dilution of precision.
     * @return hdop
     */
    public double getHdop() {
        return hdop;
    }

    /**
     * Get the vertical dilution of precision.
     * @return vdop
     */
    public double getVdop() {
        return vdop;
    }
    
    /**
     * Get the GPS time
     * @return GPS time
     */
    public Date getGpsTime() {
        return gpsTime;
    }

    /**
     * Get the UTC time
     * @return UTC time
     */
    public Date getUtcTime() {
        return utcTime;
    }

    /**
     * Number of used satellites in position
     * @return int number of used satellites
     */
    public int getNumberSatellites() {
        return numberSatellites;
    }

    /**
     * Number of tracked satellites
     * @return int number of tracked satellites
     */
    public int getNumberTrackedSatellites() {
        return numberTrackedSatellites;
    }

    /**
     * Get number of static epochs
     * @return static epoch
     */
    public int getStaticEpochs() {
        return staticEpochs;
    }

    /**
     * Get the correction age
     * @return age of the correction data in seconds
     */
    public double getCorrectionAge() {
        return correctionAge;
    }

    /**
     * Get current received correction data
     * @return received correction data sum in bytes
     */
    public long getReceivedCorrectionData() {
        return receivedCorrectionData;
    }

    /**
     * Get current RTCM station ID
     * @return station ID
     */
    public int getStationId() {
        return stationId;
    }

    /**
     * Is a datum transformation applied?
     * @return datum applied
     */
    public boolean getDatumTransformationApplied() {
        return datumTransformationApplied;
    }

    /**
     * Get the source reference frame of current position
     * @return source reference frame
     */
    public IReferenceSystem getSourceReferenceFrame() {
        return sourceReferenceFrame;
    }


    /**
     * Get the reference frame of current position
     * @return reference frame
     */
    public IReferenceSystem getReferenceFrame() {
        return referenceFrame;
    }


    /**
     * Get current Elevation above MSL is computed from height above ellipsoid,
     * using local or global geoid model (GGF file).
     * @return elevation
     */
    public double getElevation() {
        return elevation;
    }

    /**
     * Get the geoid model name
     * @return geoid model name
     */
    public String getGeoidModel() {
        return geoidModel;
    }


    public PositionUpdate( Object source, SolutionType solution, double latitude, double longitude, double height, GroundPositionType groundPositionType,
                           double heading, double horizontalVelocity, double verticalVelocity,
                           double hPrecision, double vPrecision,
                           double sigmaSemiMajorAxis, double sigmaSemiMinorAxis, double sigmaOrientation, ImuState inertialMeasurementUnitState, double pitch,
                           double roll, double yaw, double pitchPrecision, double rollPrecision, double yawPrecision,
                           double pdop, double hdop, double vdop,
                           Date gpsTime, Date utcTime, int numberSatellites, int numberTrackedSatellites, int staticEpochs, double correctionAge, long receivedCorrectionData,
                           int stationId, boolean datumTransformationApplied, IReferenceSystem sourceReferenceFrame, IReferenceSystem targetReferenceFrame,
                           double elevation, String geoidModel) {
        super(source);
        this.solution = solution;
        this.latitude = latitude;
        this.longitude = longitude;
        this.height = height;
        this.groundPositionType = groundPositionType;
        this.heading = heading;
        this.horizontalVelocity = horizontalVelocity;
        this.verticalVelocity = verticalVelocity;
        this.hPrecision = hPrecision;
        this.vPrecision = vPrecision;
        this.sigmaSemiMajorAxis = sigmaSemiMajorAxis;
        this.sigmaSemiMinorAxis = sigmaSemiMinorAxis;
        this.sigmaOrientation = sigmaOrientation;
        this.inertialMeasurementUnitState = inertialMeasurementUnitState;
        this.pitch = pitch;
        this.roll = roll;
        this.yaw = yaw;
        this.pitchPrecision = pitchPrecision;
        this.rollPrecision = rollPrecision;
        this.yawPrecision = yawPrecision;
        this.pdop = pdop;
        this.hdop = hdop;
        this.vdop = vdop;
        this.gpsTime = gpsTime;
        this.utcTime = utcTime;
        this.numberSatellites = numberSatellites;
        this.numberTrackedSatellites = numberTrackedSatellites;
        this.staticEpochs = staticEpochs;
        this.correctionAge = correctionAge;
        this.receivedCorrectionData = receivedCorrectionData;
        this.stationId = stationId;
        this.datumTransformationApplied = datumTransformationApplied;
        this.sourceReferenceFrame = sourceReferenceFrame;
        this.referenceFrame = targetReferenceFrame;
        this.elevation = elevation;
        this.geoidModel = geoidModel;
    }
  

    private final SolutionType solution;
    private final double latitude;
    private final double longitude;
    private final double height;
    private final GroundPositionType groundPositionType;
    private final double heading;
    private final double horizontalVelocity;
    private final double verticalVelocity;
    private final double hPrecision;
    private final double vPrecision;
    private final double sigmaSemiMajorAxis;
    private final double sigmaSemiMinorAxis;
    private final double sigmaOrientation;
    private final ImuState inertialMeasurementUnitState;
    private final double pitch;
    private final double roll;
    private final double yaw;
    private final double pitchPrecision;
    private final double rollPrecision;
    private final double yawPrecision;
    private final double pdop;
    private final double hdop;
    private final double vdop;
    private final Date gpsTime;
    private final Date utcTime;
    private final int numberSatellites;
    private final int numberTrackedSatellites;
    private final int staticEpochs;
    private final double correctionAge;
    private final long receivedCorrectionData;
    private final int stationId;
    private final boolean datumTransformationApplied;
    private final IReferenceSystem sourceReferenceFrame;
    private final IReferenceSystem referenceFrame;
    private final double elevation;
    private final String geoidModel;

}