package cn.wch.ch341pardemo.ui.flasher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.wch.ch341pardemo.domain.FlasherService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FlasherUiState(
    val chipName: String? = null,
    val chipSize: Int = 0,
    val chipInfo: String? = null,
    val status: String = "Ready",
    val progress: Int = 0,
    val total: Int = 1,
    val stage: String = "",
    val isBusy: Boolean = false,
    val log: List<String> = emptyList(),
    val selectedFilePath: String? = null
)

class FlasherViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(FlasherUiState())
    val state: StateFlow<FlasherUiState> = _state.asStateFlow()

    private val ctx = application.applicationContext

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stage = intent.getStringExtra(FlasherService.EXTRA_STAGE) ?: ""
            val progress = intent.getIntExtra(FlasherService.EXTRA_PROGRESS, -1)
            val total = intent.getIntExtra(FlasherService.EXTRA_TOTAL, -1)
            if (progress >= 0 && total > 0) {
                _state.update { it.copy(stage = stage, progress = progress, total = total) }
            }
        }
    }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(FlasherService.EXTRA_MESSAGE)
            val chipName = intent.getStringExtra(FlasherService.EXTRA_CHIP_NAME)
            val chipSize = intent.getIntExtra(FlasherService.EXTRA_CHIP_SIZE, 0)
            val chipInfo = intent.getStringExtra(FlasherService.EXTRA_CHIP_INFO)

            if (chipName != null) {
                _state.update { it.copy(
                    chipName = chipName,
                    chipSize = chipSize,
                    chipInfo = chipInfo,
                    status = "Detected: $chipInfo"
                )}
            }

            if (message != null) {
                appendLog(message)
            }
            _state.update { it.copy(isBusy = false, stage = "", progress = 0) }
        }
    }

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(FlasherService.EXTRA_MESSAGE) ?: "Unknown error"
            appendLog("ERROR: $message")
            _state.update { it.copy(
                status = "Error: $message",
                isBusy = false,
                stage = "",
                progress = 0
            )}
        }
    }

    init {
        val lm = androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx)
        lm.registerReceiver(progressReceiver, IntentFilter(FlasherService.ACTION_PROGRESS))
        lm.registerReceiver(resultReceiver, IntentFilter(FlasherService.ACTION_RESULT))
        lm.registerReceiver(errorReceiver, IntentFilter(FlasherService.ACTION_ERROR))
    }

    override fun onCleared() {
        super.onCleared()
        try {
            val lm = androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx)
            lm.unregisterReceiver(progressReceiver)
            lm.unregisterReceiver(resultReceiver)
            lm.unregisterReceiver(errorReceiver)
        } catch (_: Exception) {}
    }

    fun detectChip() {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, status = "Detecting chip...", log = emptyList()) }
        appendLog("Starting chip detection...")
        startService(FlasherService.ACTION_DETECT)
    }

    fun readChip(filePath: String) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, status = "Reading...") }
        appendLog("Reading flash chip to: $filePath")
        val intent = Intent(ctx, FlasherService::class.java).apply {
            action = FlasherService.ACTION_READ
            putExtra(FlasherService.EXTRA_FILE_PATH, filePath)
        }
        ctx.startService(intent)
    }

    fun eraseChip() {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, status = "Erasing...") }
        appendLog("Starting chip erase...")
        startService(FlasherService.ACTION_ERASE)
    }

    fun writeFile(filePath: String) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, status = "Writing...") }
        appendLog("Writing file to flash: $filePath")
        val intent = Intent(ctx, FlasherService::class.java).apply {
            action = FlasherService.ACTION_WRITE
            putExtra(FlasherService.EXTRA_FILE_PATH, filePath)
        }
        ctx.startService(intent)
    }

    fun verifyFile(filePath: String) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, status = "Verifying...") }
        appendLog("Verifying flash against file: $filePath")
        val intent = Intent(ctx, FlasherService::class.java).apply {
            action = FlasherService.ACTION_VERIFY
            putExtra(FlasherService.EXTRA_FILE_PATH, filePath)
        }
        ctx.startService(intent)
    }

    fun setSelectedFile(path: String?) {
        _state.update { it.copy(selectedFilePath = path) }
    }

    private fun startService(action: String) {
        val intent = Intent(ctx, FlasherService::class.java).apply {
            this.action = action
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun appendLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _state.update { it.copy(log = it.log + "[$ts] $msg") }
    }
}
