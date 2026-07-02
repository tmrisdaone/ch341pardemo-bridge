package cn.wch.ch341pardemo.domain

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.wch.ch341pardemo.TermuxBridge
import kotlinx.coroutines.flow.Flow

interface ObserveBridgeEnabled {
    operator fun invoke(): Flow<Boolean>
}

class SettingsBridgeEnabled(private val context: Context) : ObserveBridgeEnabled {
    override operator fun invoke(): Flow<Boolean> =
        cn.wch.ch341pardemo.data.SettingsRepository(context).bridgeEnabled
}

interface StartStopBridge {
    operator fun invoke(enable: Boolean)
}

class StartStopBridgeImpl(private val context: Context) : StartStopBridge {
    override operator fun invoke(enable: Boolean) {
        val intent = Intent(context, TermuxBridge::class.java)
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            context.stopService(intent)
        }
    }
}
