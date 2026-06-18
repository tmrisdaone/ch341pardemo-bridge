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
        // CH341Manager.init() registers a BroadcastReceiver for a custom
        // action ("cn.wch.uartlib.permission"). On Android 13+ the OS
        // throws SecurityException at registerReceiver time for that
        // action unless we use RECEIVER_NOT_EXPORTED. We isolate the call
        // so a library-side crash cannot kill the whole app on launch.
        try {
            CH341Manager.getInstance().init(this);
        } catch (Throwable t) {
            Log.e(TAG, "CH341Manager.init failed; USB bridge disabled at startup", t);
        }
    }

    public static Context getContext(){
        return application;
    }
}
