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

data class HomeUiState(
    val devices: List<Ch341DeviceInfo> = emptyList(),
    val vendorReady: Boolean = false,
    val bridgeEnabled: Boolean = true,
    val isRefreshing: Boolean = false
)

class HomeViewModel(
    private val repo: Ch341Repository,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeUiState(vendorReady = MyApplication.ch341Ready)
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            settings.bridgeEnabled.collect { enabled ->
                val ctx: Context = MyApplication.get()
                val intent = Intent(ctx, cn.wch.ch341pardemo.TermuxBridge::class.java)
                if (enabled) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        ctx.startForegroundService(intent)
                    } else {
                        ctx.startService(intent)
                    }
                } else {
                    ctx.stopService(intent)
                }
                _state.update { it.copy(bridgeEnabled = enabled) }
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
            return HomeViewModel(
                Ch341Repository(app),
                SettingsRepository(app)
            ) as T
        }
    }
}
