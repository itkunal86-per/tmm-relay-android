package trimble.jssi.android.catalystfacade;

import android.app.Activity;
import android.content.Intent;

import java.util.Timer;
import java.util.TimerTask;

/**
 *  Manages user token refreshing using TMM Intent - "com.trimble.tmm.RefreshUserToken"
 */
public abstract class CatalystFacadeActivity extends Activity {

    private final static long UserTokenRefreshTimePeriod = 5 * 60 * 60 * 1000;
    public final static int UserTokenRefreshRequest = 100;

    private Timer userTokenRefreshTimer;

    public  abstract String  getAppGuid();

    protected CatalystFacadeActivity() {



    }

    @Override
    protected void onResume() {
        if (userTokenRefreshTimer == null) {
            userTokenRefreshTimer = new Timer();
            userTokenRefreshTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    refreshUserToken();
                }
            }, UserTokenRefreshTimePeriod, UserTokenRefreshTimePeriod);
        }
        super.onResume();
    }

    @Override
    protected void onStop() {
        userTokenRefreshTimer.cancel();
        userTokenRefreshTimer = null;
        super.onStop();
    }


    private void refreshUserToken() {
        /*
         * Initializes the user token refresh timer,
         * using 'com.trimble.tmm.RefreshUserToken' intent of TMM.
         *
         * This intent also returns info and error in simple text format.
         * onActivityResult method receives the additional information.
         *
         */
        Intent intent = new Intent("com.trimble.tmm.RefreshUserToken");
        intent.putExtra("applicationID", getAppGuid());
        startActivityForResult(intent, UserTokenRefreshRequest);
    }
}
