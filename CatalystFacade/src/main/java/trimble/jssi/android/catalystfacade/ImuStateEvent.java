package trimble.jssi.android.catalystfacade;

import java.util.EventObject;

public class ImuStateEvent extends EventObject {
    private final ImuState imuState;

    /**
     * Constructs a prototypical Event.
     *
     * @param source The object on which the Event initially occurred.
     * @throws IllegalArgumentException if source is null.
     */
    public ImuStateEvent(Object source,ImuState imuState) {
        super(source);
        this.imuState = imuState;
    }

    public ImuState getImuState() {
        return imuState;
    }
}
