package trimble.jssi.android.catalystfacade;

public class ReturnObject<T> extends ReturnCode {

    public ReturnObject(DriverReturnCode returnCode) {
        super(returnCode);
        this.returnedObject = null;
    }
    public ReturnObject(DriverReturnCode returnCode, T returnedObject) {
        super(returnCode);
        this.returnedObject = returnedObject;
    }
    
    public T getReturnedObject() {
        return returnedObject;
    }

    private final T returnedObject;
}
