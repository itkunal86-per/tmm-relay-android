package trimble.jssi.android.catalystfacade;

import java.util.EventObject;

import trimble.jssi.connection.ConnectionState;

public class SensorStateEvent extends EventObject {
    private static final long serialVersionUID = 1L;

    public SensorStateEvent(Object source, ConnectionState sensorState) {
        super(source);
        this.sensorState = sensorState;
    }

    public ConnectionState getSensorState() {
        return sensorState;
    }

    private final ConnectionState sensorState;
}