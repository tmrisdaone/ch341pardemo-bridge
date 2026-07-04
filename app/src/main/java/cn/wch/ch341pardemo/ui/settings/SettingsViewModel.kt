package cn.wch.ch341pardemo.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.wch.ch341pardemo.data.SettingsRepository
import cn.wch.ch341pardemo.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: AppThemeMode = AppThemeMode.System,
    val bridgeEnabled: Boolean = true,
    val terminalBaud: Int = 115_200,
    val terminalShowTimestamps: Boolean = true,
    val terminalHexMode: Boolean = false,
    val uartParity: String = "None",
    val uartStopBits: String = "1",
    val uartFlowControl: String = "None",
    val spiClockSpeed: String = "1MHz",
    val spiMode: String = "0",
)

class SettingsViewModel(
    application: Application,
    private val repo: SettingsRepository
) : AndroidViewModel(application) {

    val state: StateFlow<SettingsUiState> = combine(
        repo.themeMode,
        repo.bridgeEnabled,
        repo.terminalBaud,
        repo.terminalShowTimestamps,
        repo.terminalHexMode,
        repo.uartParity,
        repo.uartStopBits,
        repo.uartFlowControl,
        repo.spiClockSpeed,
        repo.spiMode
    ) { array ->
        SettingsUiState(
            themeMode = array[0] as AppThemeMode,
            bridgeEnabled = array[1] as Boolean,
            terminalBaud = array[2] as Int,
            terminalShowTimestamps = array[3] as Boolean,
            terminalHexMode = array[4] as Boolean,
            uartParity = array[5] as String,
            uartStopBits = array[6] as String,
            uartFlowControl = array[7] as String,
            spiClockSpeed = array[8] as String,
            spiMode = array[9] as String
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    fun setThemeMode(mode: AppThemeMode) = viewModelScope.launch { repo.setThemeMode(mode) }
    fun setBridgeEnabled(enabled: Boolean) = viewModelScope.launch { repo.setBridgeEnabled(enabled) }
    fun setTerminalBaud(baud: Int) = viewModelScope.launch { repo.setTerminalBaud(baud) }
    fun setTerminalShowTimestamps(v: Boolean) = viewModelScope.launch { repo.setTerminalShowTimestamps(v) }
    fun setTerminalHexMode(v: Boolean) = viewModelScope.launch { repo.setTerminalHexMode(v) }
    fun setUartParity(parity: String) = viewModelScope.launch { repo.setUartParity(parity) }
    fun setUartStopBits(stopBits: String) = viewModelScope.launch { repo.setUartStopBits(stopBits) }
    fun setUartFlowControl(flow: String) = viewModelScope.launch { repo.setUartFlowControl(flow) }
    fun setSpiClockSpeed(speed: String) = viewModelScope.launch { repo.setSpiClockSpeed(speed) }
    fun setSpiMode(mode: String) = viewModelScope.launch { repo.setSpiMode(mode) }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(app, SettingsRepository(app)) as T
        }
    }
}
