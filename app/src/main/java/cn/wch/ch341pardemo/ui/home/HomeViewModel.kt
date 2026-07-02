package cn.wch.ch341pardemo.ui.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.wch.ch341pardemo.MyApplication
import cn.wch.ch341pardemo.data.Ch341DeviceInfo
import cn.wch.ch341pardemo.data.Ch341Repository
import cn.wch.ch341pardemo.data.SettingsRepository
import cn.wch.ch341pardemo.domain.BridgeControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class HomeUiState(
    val devices: List<Ch341DeviceInfo> = emptyList(),
    val vendorReady: Boolean = false,
    val bridgeEnabled: Boolean = true,
    val isRefreshing: Boolean = false
)

class HomeViewModel(
    private val repo: Ch341Repository,
    private val settings: SettingsRepository,
    private val control: BridgeControl = BridgeControl(MyApplication.get())
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeUiState(vendorReady = MyApplication.ch341Ready)
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            settings.bridgeEnabled.collect { enabled ->
                _state.update { it.copy(bridgeEnabled = enabled) }
                if (enabled) control.start() else control.stop()
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val devs = repo.listCh341Devices()
            _state.update { it.copy(devices = devs, isRefreshing = false) }
        }
    }

    class Factory(
        private val app: MyApplication
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(
                repo = Ch341Repository(app),
                settings = SettingsRepository(app),
                control = BridgeControl(app)
            ) as T
        }
    }
}
