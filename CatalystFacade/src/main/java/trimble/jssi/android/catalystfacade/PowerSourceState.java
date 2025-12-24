package trimble.jssi.android.catalystfacade;

public class PowerSourceState {

    /**
     * Get the battery level
     * @return battery level
     */
    public int getBatteryLevel() {
        return batteryLevel;
    }

    /**
     * is the battery charging
     * @return is battery charging
     */
    public boolean isCharging() {
        return charging;
    }
    private final int batteryLevel;
    private final boolean charging;

    public PowerSourceState(int batteryLevel, boolean charging) {
        this.batteryLevel = batteryLevel;
        this.charging = charging;
    }

}
