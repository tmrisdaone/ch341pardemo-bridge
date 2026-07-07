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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.io.readBytes

data class BiosFlashingUiState(
    val availableDevices: List<Ch341DeviceInfo> = emptyList(),
    val selectedDevice: Ch341DeviceInfo? = null,
    val selectedFileUri: Uri? = null,
    val detectedJedecId: String? = null,
    val isDetecting: Boolean = false,
    val isRequestingReadUri: Boolean = false,
    val isReading: Boolean = false,
    val isRequestingBackupUri: Boolean = false,
    val isBackingUp: Boolean = false,
    val isFlashing: Boolean = false,
    val progress: Float = 0f,
    val log: List<String> = emptyList(),
    val errorMessage: String? = null
)

class BiosFlashingViewModel(
    private val application: Application,
    private val repo: Ch341Repository
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(BiosFlashingUiState())
    val state: StateFlow<BiosFlashingUiState> = _state.asStateFlow()

    private val usbManager: UsbManager =
        application.getSystemService(Context.USB_SERVICE) as UsbManager

    // Holds the bytes between Read picker launch and uri selection.
    // Kept in-memory only — capped at the 1 KB sample size.
    private var pendingReadBytes: ByteArray? = null

    // Backing Job for the currently running long op so the user could cancel later.
    @Suppress("unused")
    private var currentOp: Job? = null

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
        _state.update {
            // Clear stale chip detection when device changes.
            it.copy(selectedDevice = device, detectedJedecId = null)
        }
    }

    fun selectFile(uri: Uri) {
        _state.update { it.copy(selectedFileUri = uri) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun detectChip() {
        val device = _state.value.selectedDevice ?: run {
            _state.update { it.copy(errorMessage = "Select a CH341 device first") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isDetecting = true, log = it.log + "Detecting chip...") }
            val result = withContext(Dispatchers.IO) {
                val usb = repo.getDevice(device.deviceId) ?: return@withContext null
                if (!repo.hasPermission(usb)) {
                    requestPermission(usb)
                    return@withContext "PERMISSION_REQUIRED"
                }
                repo.detectSpiChip(usb)
            }
            _state.update { state ->
                when {
                    result == null -> state.copy(
                        isDetecting = false,
                        errorMessage = "Could not communicate with the device"
                    )
                    result == "PERMISSION_REQUIRED" -> state.copy(
                        isDetecting = false,
                        errorMessage = "USB permission required — please reconnect the device"
                    )
                    else -> state.copy(
                        isDetecting = false,
                        detectedJedecId = result,
                        log = state.log + "Detected chip: $result"
                    )
                }
            }
        }
    }

    fun readBios() {
        val device = _state.value.selectedDevice ?: run {
            _state.update { it.copy(errorMessage = "Select a CH341 device first") }
            return
        }
        // Launch the picker for save destination first; bytes are read in onReadUriSelected.
        _state.update {
            it.copy(
                isRequestingReadUri = true,
                log = it.log + "Choose destination for 1 KB BIOS sample..."
            )
        }
    }

    /**
     * Caller of the read-sample picker hands the chosen URI here.
     * Reads 1 KB (the sample) and writes it to the picked file.
     */
    fun onReadUriSelected(uri: Uri) {
        val device = _state.value.selectedDevice ?: return
        viewModelScope.launch {
            _state.update { it.copy(isRequestingReadUri = false, isReading = true, progress = 0f) }
            val success = withContext(Dispatchers.IO) {
                try {
                    val usb = repo.getDevice(device.deviceId) ?: return@withContext false
                    if (!repo.hasPermission(usb)) {
                        requestPermission(usb)
                        return@withContext false
                    }
                    val data = repo.readSpiFlash(usb, 0L, 1024) ?: return@withContext false
                    val resolver = application.contentResolver
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(data)
                    } ?: return@withContext false
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (success) {
                _state.update {
                    it.copy(
                        isReading = false,
                        progress = 1f,
                        log = it.log + "Read sample saved (1024 bytes)"
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        isReading = false,
                        errorMessage = "Read failed — check USB connection and logs"
                    )
                }
            }
        }
    }

    fun backupBios() {
        val device = _state.value.selectedDevice ?: run {
            _state.update { it.copy(errorMessage = "Select a CH341 device first") }
            return
        }
        _state.update {
            it.copy(
                isRequestingBackupUri = true,
                log = it.log + "Choose destination for full backup..."
            )
        }
    }

    fun onBackupUriSelected(uri: Uri) {
        val device = _state.value.selectedDevice ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isRequestingBackupUri = false,
                    isBackingUp = true,
                    progress = 0f,
                    log = it.log + "Backup started..."
                )
            }

            val success = withContext(Dispatchers.IO) {
                try {
                    val usb = repo.getDevice(device.deviceId) ?: return@withContext false
                    if (!repo.hasPermission(usb)) {
                        requestPermission(usb)
                        return@withContext false
                    }
                    val resolver = application.contentResolver
                    val ok = resolver.openOutputStream(uri)?.use { outputStream ->
                        repo.backupFullFlash(usb, outputStream)
                    } ?: false
                    ok
                } catch (e: Exception) {
                    false
                }
            }

            if (success) {
                _state.update {
                    it.copy(
                        isBackingUp = false,
                        progress = 1f,
                        log = it.log + "Backup complete ✅"
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        isBackingUp = false,
                        errorMessage = "Backup failed — check USB connection and logs"
                    )
                }
            }
        }
    }

    fun startFlashing() {
        val device = _state.value.selectedDevice ?: return
        val uri = _state.value.selectedFileUri ?: return

        viewModelScope.launch {
            _state.update {
                it.copy(isFlashing = true, progress = 0f, log = it.log + "Starting flash process...")
            }

            val success = withContext(Dispatchers.IO) {
                performFlash(device, uri)
            }

            if (success) {
                _state.update {
                    it.copy(
                        isFlashing = false,
                        progress = 1f,
                        log = it.log + "Flash complete ✅"
                    )
                }
            } else {
                _state.update {
                    it.copy(isFlashing = false, errorMessage = "Flash failed. Check logs.")
                }
            }
        }
    }

    private suspend fun performFlash(deviceInfo: Ch341DeviceInfo, uri: Uri): Boolean {
        val usbDevice = repo.getDevice(deviceInfo.deviceId) ?: return false
        if (!repo.hasPermission(usbDevice)) return false

        return try {
            val resolver = application.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("Could not read file from URI")

            val pageSize = 256
            val sectorSize = 4096

            for (i in 0 until bytes.size step pageSize) {
                val end = minOf(i + pageSize, bytes.size)
                val chunk = bytes.sliceArray(i until end)

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
                    _state.update {
                        it.copy(progress = progress, log = it.log + "Flashed bytes $i..$end")
                    }
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

    private fun requestPermission(usb: UsbDevice) {
        try {
            val intent = repo.buildPermissionIntent()
            repo.requestPermission(usb, intent)
        } catch (_: Exception) {
            // permission flow surfaces via OS dialog; nothing else to do here
        }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return BiosFlashingViewModel(app, Ch341Repository(app)) as T
        }
    }
}
