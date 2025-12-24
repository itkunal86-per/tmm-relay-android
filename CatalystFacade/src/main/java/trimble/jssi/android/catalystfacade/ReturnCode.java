package trimble.jssi.android.catalystfacade;

public class ReturnCode {

    public DriverReturnCode getCode() {
        return code;
    }
    
    public void setReturnCode(DriverReturnCode code) {
        this.code = code;
    }

    public ReturnCode(DriverReturnCode code) {
        super();
        this.code = code;
    }
    

    private DriverReturnCode code;
}
