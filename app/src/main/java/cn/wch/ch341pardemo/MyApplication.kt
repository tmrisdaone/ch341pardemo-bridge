package cn.wch.ch341pardemo

import android.app.Application
import android.content.Context
import android.util.Log
import cn.wch.ch341lib.CH341Manager

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // CH341Manager.init() (vendored CH341PARV1.1.jar) registers a
        // BroadcastReceiver for the custom action "cn.wch.uartlib.permission".
        // On Android 13+ registerReceiver(..., RECEIVER_EXPORTED) throws
        // SecurityException for non-protected custom actions. Catching here
        // ensures a library-side failure cannot kill the app on launch —
        // manual Open Device from the Devices screen still works.
        try {
            CH341Manager.getInstance().init(this)
            ch341Ready = true
            Log.i(TAG, "CH341Manager initialized")
        } catch (se: SecurityException) {
            Log.w(TAG, "CH341Manager.init blocked by SecurityException; " +
                "USB device-attach broadcast disabled, manual Open Device still works", se)
        } catch (re: RuntimeException) {
            Log.e(TAG, "CH341Manager.init failed; USB bridge degraded", re)
        }
    }

    companion object {
        private const val TAG = "MyApplication"
        @Volatile var ch341Ready: Boolean = false
            private set
        private lateinit var instance: MyApplication
        fun get(): Context = instance.applicationContext
    }
}
