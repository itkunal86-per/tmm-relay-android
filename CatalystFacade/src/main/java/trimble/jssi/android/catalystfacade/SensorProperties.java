package trimble.jssi.android.catalystfacade;

public class SensorProperties {

    public SensorProperties(String instrumentName, String serialNumber, String firmware, boolean licensed) {
        super();
        this.instrumentName = instrumentName;
        this.serialNumber = serialNumber;
        this.firmware = firmware;
        this.licensed = licensed;
    }

    public String getInstrumentName() {
        return instrumentName;
    }
    public String getSerialNumber() {
        return serialNumber;
    }
    public String getFirmware() {
        return firmware;
    }

    public boolean isLicensed() {
        return licensed;
    }

    private final String instrumentName;
    private final String serialNumber;
    private final String firmware;
    private boolean licensed;

}
