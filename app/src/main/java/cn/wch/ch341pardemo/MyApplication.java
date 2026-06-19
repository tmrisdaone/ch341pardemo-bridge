package cn.wch.ch341pardemo;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import cn.wch.ch341lib.CH341Manager;

public class MyApplication extends Application {
    private static final String TAG = "MyApplication";
    private static Application application;
    @Override
    public void onCreate() {
        super.onCreate();
        application=this;
        // CH341Manager.init() (vendored CH341PARV1.1.jar) registers a
        // BroadcastReceiver for the custom action "cn.wch.uartlib.permission".
        // On Android 13+ registerReceiver(..., RECEIVER_EXPORTED) throws
        // SecurityException for non-protected custom actions. The fix has
        // three parts:
        //   1. Manifest declares <uses-permission android:name="cn.wch.uartlib.permission"/>
        //      so the action is locally-known.
        //   2. We catch SecurityException (and other RuntimeExceptions) so
        //      a library-side failure cannot kill the whole app on launch.
        //   3. We expose a CH341Manager.isReady() flag for the activity to
        //      disable USB controls gracefully.
        try {
            CH341Manager.getInstance().init(this);
        } catch (SecurityException se) {
            Log.w(TAG, "CH341Manager.init blocked by SecurityException; " +
                    "USB device-attach broadcast disabled, manual Open Device still works", se);
        } catch (RuntimeException re) {
            Log.e(TAG, "CH341Manager.init failed; USB bridge degraded", re);
        }
    }

    public static Context getContext(){
        return application;
    }
}
