package cn.wch.ch341pardemo.ui.terminal

import android.app.Application
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.wch.ch341pardemo.data.Ch341Repository
import cn.wch.ch341pardemo.data.HexUtil
import cn.wch.ch341pardemo.data.SettingsRepository
import cn.wch.ch341pardemo.data.TerminalLine
import cn.wch.ch341pardemo.data.UartConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

data class TerminalUiState(
    val config: UartConfig = UartConfig(),
    val isOpen: Boolean = false,
    val connectedDevice: Ch341DeviceInfo? = null,
    val lines: List<TerminalLine> = emptyList(),
    val pendingInput: String = "",
    val hexMode: Boolean = false,
    val showTimestamps: Boolean = true,
    val isReading: Boolean = false,
    val errorMessage: String? = null
) {
    val canSend: Boolean get() = isOpen && pendingInput.isNotEmpty()
}

class TerminalViewModel(
    application: Application,
    private val repo: Ch341Repository,
    private val settings: SettingsRepository
) : AndroidViewModel(application) {

    private val usbManager: UsbManager =
        application.getSystemService(USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var intf: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    private var readJob: Job? = null
    private val rxBufferSize = 4096

    init {
        viewModelScope.launch {
            settings.terminalBaud.collect { baud ->
                _state.update { it.copy(config = it.config.copy(baudRate = baud)) }
            }
        }
        viewModelScope.launch {
            settings.terminalHexMode.collect { hex ->
                _state.update { it.copy(hexMode = hex) }
            }
        }
        viewModelScope.launch {
            settings.terminalShowTimestamps.collect { show ->
                _state.update { it.copy(showTimestamps = show) }
            }
        }
    }

    fun selectDevice(info: Ch341DeviceInfo) {
        if (_state.value.isOpen) return
        _state.update { it.copy(connectedDevice = info) }
    }

    fun openConnection() {
        if (_state.value.isOpen) return
        val dev = _state.value.connectedDevice ?: run {
            _state.update { it.copy(errorMessage = "Select a device first") }
            return
        }
        val usb = repo.getDevice(dev.deviceId) ?: run {
            _state.update { it.copy(errorMessage = "Device not found on USB bus") }
            return
        }
        if (!repo.hasPermission(usb)) {
            _state.update { it.copy(errorMessage = "USB permission not granted") }
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { openUsbRaw(usb) }
            if (!ok) {
                _state.update { it.copy(errorMessage = "Failed to open USB endpoint") }
                return@launch
            }
            _state.update { it.copy(isOpen = true, errorMessage = null) }
            appendInfo("Connected: VID ${"%04X".format(dev.vendorId)} PID ${"%04X".format(dev.productId)} @ ${_state.value.config.baudRate} baud")
            startReadLoop()
        }
    }

    private fun openUsbRaw(d: UsbDevice): Boolean {
        device = d
        val conn = usbManager.openDevice(d) ?: return false
        connection = conn
        if (d.interfaceCount == 0) {
            conn.close()
            return false
        }
        val iface = d.getInterface(0)
        intf = iface
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            return false
        }
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == UsbEndpoint.Direction.IN && inEp == null) inEp = ep
            if (ep.direction == UsbEndpoint.Direction.OUT && outEp == null) outEp = ep
        }
        epIn = inEp
        epOut = outEp
        return inEp != null && outEp != null
    }

    fun closeConnection() {
        readJob?.cancel()
        readJob = null
        val conn = connection
        val iface = intf
        if (conn != null && iface != null) {
            try { conn.releaseInterface(iface) } catch (_: Throwable) {}
        }
        conn?.close()
        connection = null
        intf = null
        epIn = null
        epOut = null
        device = null
        _state.update { it.copy(isOpen = false, isReading = false) }
        appendInfo("Disconnected")
    }

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isReading = true) }
            val conn = connection ?: return@launch
            val ep = epIn ?: return@launch
            val buf = ByteBuffer.allocate(rxBufferSize)
            while (isActive && _state.value.isOpen) {
                val req = UsbRequest()
                req.initialize(conn, ep)
                buf.clear()
                try {
                    if (!req.queue(buf)) {
                        conn.requestWait() // drain a stuck request
                        continue
                    }
                    conn.requestWait()
                    val n = buf.position()
                    if (n > 0) {
                        val data = ByteArray(n)
                        buf.flip()
                        buf.get(data)
                        appendLine(TerminalLine(System.currentTimeMillis(), TerminalLine.Direction.RX, data))
                    }
                } catch (t: Throwable) {
                    appendLine(TerminalLine(
                        System.currentTimeMillis(),
                        TerminalLine.Direction.ERROR,
                        ByteArray(0),
                        "Read error: ${t.message}"
                    ))
                    break
                } finally {
                    try { req.close() } catch (_: Throwable) {}
                }
            }
            _state.update { it.copy(isReading = false) }
        }
    }

    fun updateInput(s: String) {
        _state.update { it.copy(pendingInput = s) }
    }

    fun send() {
        val text = _state.value.pendingInput
        if (text.isBlank() || !_state.value.isOpen) return
        val bytes = if (_state.value.hexMode) HexUtil.hexToBytes(text) else text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) {
            _state.update { it.copy(errorMessage = "Empty payload") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val n = writeRaw(bytes)
            if (n > 0) {
                appendLine(TerminalLine(System.currentTimeMillis(), TerminalLine.Direction.TX, bytes))
                _state.update { it.copy(pendingInput = "", errorMessage = null) }
            } else {
                _state.update { it.copy(errorMessage = "Write failed (n=$n)") }
            }
        }
    }

    fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty() || !_state.value.isOpen) return
        viewModelScope.launch(Dispatchers.IO) {
            val n = writeRaw(bytes)
            if (n > 0) {
                appendLine(TerminalLine(System.currentTimeMillis(), TerminalLine.Direction.TX, bytes))
            }
        }
    }

    private fun writeRaw(bytes: ByteArray): Int {
        val conn = connection ?: return -1
        val ep = epOut ?: return -1
        // CH341 is bulk. bulkTransfer blocks up to timeout.
        val sent = conn.bulkTransfer(ep, bytes, min(bytes.size, 4096), 1000)
        return sent
    }

    fun setBaud(b: Int) {
        _state.update { it.copy(config = it.config.copy(baudRate = b)) }
        viewModelScope.launch { settings.setTerminalBaud(b) }
        // Note: the vendored CH341 lib can change baud on the fly; for the
        // raw-endpoint path we'd need a USB control transfer (SET_LINE_CODING
        // for CDC ACM, or vendor-specific for CH341). For now we just store
        // the new value; a real implementation would issue a control xfer.
    }

    fun setHexMode(hex: Boolean) {
        _state.update { it.copy(hexMode = hex) }
        viewModelScope.launch { settings.setTerminalHexMode(hex) }
    }

    fun setShowTimestamps(show: Boolean) {
        _state.update { it.copy(showTimestamps = show) }
        viewModelScope.launch { settings.setTerminalShowTimestamps(show) }
    }

    fun clearLog() {
        _state.update { it.copy(lines = emptyList()) }
    }

    fun exportLog(): String {
        val s = _state.value
        val sb = StringBuilder()
        for (line in s.lines) {
            if (s.showTimestamps) sb.append(HexUtil.formatTimestamp(line.timestampMs)).append(' ')
            sb.append(line.direction.name.ljust(5))
            sb.append(' ')
            sb.append(if (s.hexMode) HexUtil.bytesToHex(line.bytes, " ") else String(line.bytes, Charsets.UTF_8))
            if (line.note != null) sb.append("  // ").append(line.note)
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun String.ljust(width: Int): String =
        if (length >= width) this else this + " ".repeat(width - length)

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun appendInfo(text: String) {
        appendLine(TerminalLine(
            System.currentTimeMillis(),
            TerminalLine.Direction.INFO,
            text.toByteArray(Charsets.UTF_8)
        ))
    }

    private fun appendLine(l: TerminalLine) {
        _state.update { s ->
            val trimmed = if (s.lines.size > 2000) s.lines.drop(s.lines.size - 2000) else s.lines
            s.copy(lines = trimmed + l)
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeConnection()
    }

    class Factory(
        private val app: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TerminalViewModel(
                app,
                Ch341Repository(app),
                SettingsRepository(app)
            ) as T
        }
    }
}
