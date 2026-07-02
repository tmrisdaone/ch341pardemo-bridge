package cn.wch.ch341pardemo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.wch.ch341pardemo.MyApplication
import cn.wch.ch341pardemo.data.Ch341DeviceInfo
import cn.wch.ch341pardemo.data.Ch341Repository
import cn.wch.ch341pardemo.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cn.wch.ch341pardemo.domain.SetBridgeEnabled

data class HomeUiState(
    val devices: List<Ch341DeviceInfo> = emptyList(),
    val vendorReady: Boolean = false,
    val bridgeEnabled: Boolean = true,
    val isRefreshing: Boolean = false
)

class HomeViewModel(
    private val repo: Ch341Repository,
    private val settings: SettingsRepository,
    private val bridgeControl: SetBridgeEnabled = SetBridgeEnabled(MyApplication.get())
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
                bridgeControl(enabled)
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val devs = withContext(Dispatchers.IO) { repo.listCh341Devices() }
            _state.update { it.copy(devices = devs, isRefreshing = false) }
        }
    }

    class Factory(
        private val app: MyApplication
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val controller = AndroidBridgeController(app)
            val observeEnabled: ObserveBridgeSettings = SettingsBridgeEnabled(app)
            return HomeViewModel(
                repo = Ch341Repository(app),
                settings = SettingsRepository(app),
                bridgeControl = SetBridgeEnabled(controller, observeEnabled)
            ) as T
        }
    }
}
