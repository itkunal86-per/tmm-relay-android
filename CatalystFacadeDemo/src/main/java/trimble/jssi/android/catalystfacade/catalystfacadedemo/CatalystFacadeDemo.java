package trimble.jssi.android.catalystfacade.catalystfacadedemo;

import android.app.Application;

public class CatalystFacadeDemo extends Application {

    @Override
    public void onCreate() {
         super.onCreate();
         MainModel.getInstance().init(this.getApplicationContext());
    }

}
