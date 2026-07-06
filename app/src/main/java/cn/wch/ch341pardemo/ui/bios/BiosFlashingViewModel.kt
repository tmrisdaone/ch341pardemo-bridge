package cn.wch.ch341pardemo.ui.bios

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
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
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.logging.Logger

data class BiosFlashingUiState(
    val availableDevices: List<Ch341DeviceInfo> = emptyList(),
    val selectedDevice: Ch341DeviceInfo? = null,
    val selectedFileUri: Uri? = null,
    val isFlashing: Boolean = false,
    val progress: Float = 0f,
    val log: List<String> = emptyList(),
    val errorMessage: String? = null,
    val isRequestingBackupUri: Boolean = false
)

class BiosFlashingViewModel(
    application: Application,
    private val repo: Ch341Repository
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(BiosFlashingUiState())
    val state: StateFlow<BiosFlashingUiState> = _state.asStateFlow()

    private val usbManager: UsbManager =
        application.getSystemService(Context.USB_SERVICE) as UsbManager

    init {
        refreshDevices()
    }

    fun refreshDevices() {
        viewModelScope.launch {
            val devices = withContext(Dispatchers.IO) { repo.listCh341Devices() }
            _state.update { it.copy(availableDevices = devices) }
        }
    }

    fun selectDevice(device: Ch341DeviceInfo) {
        _state.update { it.copy(selectedDevice = device) }
    }

    fun selectFile(uri: Uri) {
        _state.update { it.copy(selectedFileUri = uri) }
    }

    fun detectChip() {
        val device = _state.value.selectedDevice ?: return
        viewModelScope.launch {
            _state.update { it.copy(log = it.log + "Detecting chip...") }
            val id = withContext(Dispatchers.IO) {
                val usb = repo.getDevice(device.deviceId) ?: return@withContext "Device not found"
                repo.detectSpiChip(usb) ?: "Unknown Chip"
            }
            _state.update { it.copy(log = it.log + "Result: $id") }
        }
    }

    fun readBios() {
        val device = _state.value.selectedDevice ?: return
        viewModelScope.launch {
            _state.update { it.copy(log = it.log + "Reading BIOS start...") }
            val data = withContext(Dispatchers.IO) {
                val usb = repo.getDevice(device.deviceId) ?: return@withContext null
                repo.readSpiFlash(usb, 0, 1024) // Read first 1KB as sample
            }
            if (data != null) {
                _state.update { it.copy(log = it.log + "Read success: ${data.size} bytes") }
            } else {
                _state.update { it.copy(errorMessage = "Failed to read BIOS") }
            }
        }
    }

    fun backupBios() {
        val device = _state.value.selectedDevice ?: return
        _state.update { it.copy(
            isRequestingBackupUri = true,
            log = it.log + "Requesting destination file for backup..."
        ) }
    }

    fun onBackupUriSelected(uri: Uri) {
        val device = _state.value.selectedDevice ?: return
        viewModelScope.launch {
            _state.update { it.copy(
                isRequestingBackupUri = false,
                isFlashing = true,
                progress = 0f,
                log = it.log + "Backup started..."
            ) }

            val success = withContext(Dispatchers.IO) {
                try {
                    val usb = repo.getDevice(device.deviceId) ?: return@withContext false
                    if (!repo.hasPermission(usb)) return@withContext false

                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        repo.backupFullFlash(usb, outputStream)
                    } ?: false
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(log = it.log + "Backup error: ${e.message}") }
                    }
                    false
                }
            }

            if (success) {
                _state.update { it.copy(isFlashing = false, progress = 1f, log = it.log + "Backup complete! ✅") }
            } else {
                _state.update { it.copy(isFlashing = false, errorMessage = "Backup failed. Check logs.") }
            }
        }
    }

    fun startFlashing() {
        val device = _state.value.selectedDevice ?: return
        val uri = _state.value.selectedFileUri ?: return

        viewModelScope.launch {
            _state.update { it.copy(isFlashing = true, progress = 0f, log = listOf("Starting flash process...")) }

            val success = withContext(Dispatchers.IO) {
                performFlash(device, uri)
            }

            if (success) {
                _state.update { it.copy(isFlashing = false, progress = 1f, log = _state.value.log + "Flash complete! ✅") }
            } else {
                _state.update { it.copy(isFlashing = false, errorMessage = "Flash failed. Check logs.") }
            }
        }
    }

    private suspend fun performFlash(deviceInfo: Ch341DeviceInfo, uri: Uri): Boolean {
        val usbDevice = repo.getDevice(deviceInfo.deviceId) ?: return false
        if (!repo.hasPermission(usbDevice)) return false

        return try {
            val resolver = getApplication<Application>().contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("Could not read file from URI")

            val pageSize = 256
            val sectorSize = 4096

            for (i in 0 until bytes.size step pageSize) {
                val end = minOf(i + pageSize, bytes.size)
                val chunk = bytes.sliceArray(i until end)

                // Erase sector if at sector boundary
                if (i % sectorSize == 0) {
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(log = it.log + "Erasing sector at $i...") }
                    }
                    if (!repo.eraseSpiFlash(usbDevice, i.toLong())) {
                        throw Exception("Failed to erase sector at $i")
                    }
                }

                if (!repo.writeSpiFlash(usbDevice, i.toLong(), chunk)) {
                    throw Exception("Failed to write page at $i")
                }

                val progress = i.toFloat() / bytes.size
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(progress = progress, log = it.log + "Flashed bytes $i..$end") }
                }
            }
            true
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _state.update { it.copy(log = it.log + "Error: ${e.message}") }
            }
            false
        }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return BiosFlashingViewModel(app, Ch341Repository(app)) as T
        }
    }
}
