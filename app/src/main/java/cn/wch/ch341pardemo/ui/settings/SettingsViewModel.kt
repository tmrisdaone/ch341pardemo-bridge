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
)

class SettingsViewModel(
    application: Application,
    private val repo: SettingsRepository
) : AndroidViewModel(application) {

    val state: StateFlow<SettingsUiState> = combine(
        repo.themeMode,
        repo.bridgeEnabled,
        repo.terminalBaud,
        combine(
            repo.terminalShowTimestamps,
            repo.terminalHexMode,
        ) { show, hex -> show to hex }
    ) { theme, bridge, baud, terminal ->
        SettingsUiState(
            themeMode = theme,
            bridgeEnabled = bridge,
            terminalBaud = baud,
            terminalShowTimestamps = terminal.first,
            terminalHexMode = terminal.second,
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

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(app, SettingsRepository(app)) as T
        }
    }
}
