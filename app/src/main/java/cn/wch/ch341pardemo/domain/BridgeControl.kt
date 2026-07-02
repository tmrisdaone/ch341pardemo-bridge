package cn.wch.ch341pardemo.domain

import android.content.Context
import android.content.Intent
import android.os.Build
import cn.wch.ch341pardemo.TermuxBridge

class BridgeControl(private val context: Context) {

    fun start() {
        val intent = Intent(context, TermuxBridge::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop() {
        val intent = Intent(context, TermuxBridge::class.java)
        context.stopService(intent)
    }
}
