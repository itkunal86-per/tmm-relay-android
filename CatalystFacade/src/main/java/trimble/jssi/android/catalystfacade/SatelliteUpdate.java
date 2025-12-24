package trimble.jssi.android.catalystfacade;

import java.util.EventObject;
import java.util.List;

import trimble.jssi.interfaces.gnss.satellites.ISatellite;

public class SatelliteUpdate extends EventObject {
    private static final long serialVersionUID = 1L;

    private final List<ISatellite> satellites;

    public SatelliteUpdate(Object source, List<ISatellite> satellites) {
        super(source);
        this.satellites = satellites;
    }

    public List<ISatellite> getSatellites() {
        return satellites;
    }
}