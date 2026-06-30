package cn.wch.ch341pardemo

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import cn.wch.ch341lib.CH341Manager
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        initCH341()
    }

    // ── CH341 init ──────────────────────────────────────────────
    private fun initCH341() {
        try {
            CH341Manager.getInstance().init(this)
            ch341Ready = true
            Log.i(TAG, "CH341Manager initialized")
        } catch (se: SecurityException) {
            Log.w(TAG, "CH341Manager.init blocked by SecurityException; " +
                "USB device-attach broadcast disabled, manual Open Device still works", se)
        } catch (re: RuntimeException) {
            Log.e(TAG, "CH341Manager.init failed; USB bridge degraded", re)
        } catch (t: Throwable) {
            // Catch-ALL: any other exception type (e.g. UnsatisfiedLinkError
            // from missing .so, NoClassDefFoundError, etc.) no longer kills
            // the app silently.
            Log.e(TAG, "CH341Manager.init threw unexpected ${t.javaClass.simpleName}", t)
        }
    }

    // ── Crash logger ───────────────────────────────────────────
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 1. Write full stack trace to crash log file
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val logFile = File(filesDir, "crash_logs/crash_$ts.txt")
            logFile.parentFile?.mkdirs()
            try {
                FileWriter(logFile, true).use { writer ->
                    PrintWriter(writer).use { pw ->
                        pw.println("═══════════════════════════════════════")
                        pw.println("CRASH  $ts  thread=${thread.name}")
                        pw.println("═══════════════════════════════════════")
                        throwable.printStackTrace(pw)
                        pw.println()
                    }
                }
                Log.e(TAG, "Crash log saved to ${logFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log", e)
            }

            // 2. Toast on UI thread so user sees something happened
            try {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        this,
                        "App crashed — log saved to ${logFile.name}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: Exception) {}

            // 3. Let the default handler finish (kills process as normal)
            defaultHandler?.uncaughtException(thread, throwable)
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
