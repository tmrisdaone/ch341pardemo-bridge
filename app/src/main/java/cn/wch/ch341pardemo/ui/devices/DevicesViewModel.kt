package cn.wch.ch341pardemo.ui.devices

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.wch.ch341pardemo.data.Ch341DeviceInfo
import cn.wch.ch341pardemo.data.Ch341Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DevicesUiState(
    val devices: List<Ch341DeviceInfo> = emptyList(),
    val allUsb: List<Ch341DeviceInfo> = emptyList(),
    val hasPermission: Set<Int> = emptySet(),
    val pendingPermissionFor: Int? = null,
    val isRefreshing: Boolean = false,
    val snackbar: String? = null
)

class DevicesViewModel(
    application: Application,
    private val repo: Ch341Repository
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    private val usbManager: UsbManager =
    application.getSystemService(Context.USB_SERVICE) as UsbManager

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val id = device?.deviceId ?: _state.value.pendingPermissionFor
            if (id != null) {
                _state.update { s ->
                    val newSet = if (granted) s.hasPermission + id else s.hasPermission - id
                    s.copy(
                        hasPermission = newSet,
                        pendingPermissionFor = null,
                        snackbar = if (granted) "USB permission granted" else "USB permission denied"
                    )
                }
            }
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
        }
    }

    init {
        val ctx = getApplication<Application>()
        val filter = IntentFilter("cn.wch.ch341pardemo.USB_PERMISSION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(permissionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(permissionReceiver, filter)
        }
        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(attachReceiver, attachFilter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(attachReceiver, attachFilter)
        }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { repo.listDevices() }
            val ch = withContext(Dispatchers.IO) { repo.listCh341Devices() }
            val withPerm = all.filter { usbManager.hasPermission(repo.getDevice(it.deviceId) ?: return@filter false) }
                .map { it.deviceId }
                .toSet()
            _state.update {
                it.copy(
                    allUsb = all,
                    devices = ch,
                    hasPermission = withPerm,
                    isRefreshing = false
                )
            }
        }
    }

    fun requestPermission(info: Ch341DeviceInfo) {
        val d = repo.getDevice(info.deviceId) ?: return
        val intent = repo.buildPermissionIntent()
        _state.update { it.copy(pendingPermissionFor = info.deviceId) }
        repo.requestPermission(d, intent)
    }

    fun dismissSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { getApplication<Application>().unregisterReceiver(permissionReceiver) }
        runCatching { getApplication<Application>().unregisterReceiver(attachReceiver) }
    }

    class Factory(
        private val app: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DevicesViewModel(app, Ch341Repository(app)) as T
        }
    }
}
