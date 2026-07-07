package cn.wch.ch341pardemo.data

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cn.wch.ch341lib.CH341Manager
import cn.wch.ch341lib.exception.CH341LibException

/**
 * Snapshot of a CH341-compatible USB device.
 */
data class Ch341DeviceInfo(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val manufacturerName: String?,
    val serialNumber: String?,
    val interfaceCount: Int
) {
    val isCh341: Boolean get() = vendorId == 0x1A86
}

object Ch341VidPid {
    const val VID = 0x1A86
    // CH341 PIDs that this app knows about
    val PIDS = setOf(
        0x7523, 0x5523, 0x7522, 0x5512,
        0x7584, 0x7585, 0x7586,
        0xE023, 0xE024, 0xE025   // newer CH341 variants
    )
}

/**
 * Single source of truth for CH341 USB device discovery and permission.
 * Thin Kotlin wrapper around the system UsbManager + the vendored
 * CH341Manager library.
 */
class Ch341Repository(private val context: Context) {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    /**
     * Snapshot of currently attached USB devices. Cheap to call; no I/O.
     */
    fun listDevices(): List<Ch341DeviceInfo> {
        val map = usbManager.deviceList ?: return emptyList()
        return map.values
            .map { d ->
                Ch341DeviceInfo(
                    deviceId = d.deviceId,
                    vendorId = d.vendorId,
                    productId = d.productId,
                    productName = d.productName,
                    manufacturerName = d.manufacturerName,
                    serialNumber = d.serialNumber,
                    interfaceCount = d.interfaceCount
                )
            }
            .sortedBy { it.deviceId }
    }

    fun listCh341Devices(): List<Ch341DeviceInfo> =
        listDevices().filter { it.isCh341 && it.productId in Ch341VidPid.PIDS }

    fun getDevice(deviceId: Int): UsbDevice? =
        usbManager.deviceList?.values?.firstOrNull { it.deviceId == deviceId }

    fun hasPermission(device: UsbDevice): Boolean =
        usbManager.hasPermission(device)

    /**
     * Returns a PendingIntent for the system USB permission dialog.
     * Caller is responsible for passing it to usbManager.requestPermission().
     */
    fun buildPermissionIntent(): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            context, 0,
            android.content.Intent("cn.wch.ch341pardemo.USB_PERMISSION"),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun requestPermission(device: UsbDevice, permissionIntent: android.app.PendingIntent) {
        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Hand a device off to the vendored CH341Manager so it can do its
     * magic (init internal lib, return handle for higher-level ops).
     * Returns true if the lib accepted the device.
     */
    fun handOffToVendor(device: UsbDevice): Boolean {
       val mgr = CH341Manager.getInstance()
       if (!mgr.isConnected(device)) {
           return try {
               mgr.openDevice(device)
           } catch (t: Throwable) {
               false
           }
       }
       return mgr.isConnected(device)
    }

    /**
     * Detects the SPI flash chip connected to the adapter.
     * Returns the 3-byte JEDEC ID as a hex string (e.g. "EF4017" for W25Q64),
     * or null if no chip responded.
     */
    fun detectSpiChip(device: UsbDevice): String? {
        if (!handOffToVendor(device)) return null
        return try {
            val mgr = CH341Manager.getInstance()
            mgr.CH34xSetParaMode(device, SPI_PARA_MODE)
            // RDID (0x9F) returns 3 bytes clocked out on the next 3 SCLK edges.
            // SPI is full-duplex: send 4 bytes (cmd + 3 dummy 0xFF), read 4 bytes.
            // ID lands at recvBuf[1..3]; recvBuf[0] is garbage from the cmd byte.
            val sendBuf = byteArrayOf(0x9F.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
            val recvBuf = ByteArray(4)
            if (!spiTransfer(device, sendBuf, recvBuf)) return null
            "%02X%02X%02X".format(recvBuf[1], recvBuf[2], recvBuf[3])
        } catch (e: CH341LibException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads a block of data from the SPI flash.
     * The first 4 bytes of the SPI response (cmd + 3 address bytes) are
     * garbage; the actual data starts at offset 4.
     */
    fun readSpiFlash(device: UsbDevice, address: Long, length: Int): ByteArray? {
        if (length <= 0) return null
        if (!handOffToVendor(device)) return null
        return try {
            val mgr = CH341Manager.getInstance()
            mgr.CH34xSetParaMode(device, SPI_PARA_MODE)

            val addrHigh = ((address shr 16) and 0xFF).toByte()
            val addrMid = ((address shr 8) and 0xFF).toByte()
            val addrLow = (address and 0xFF).toByte()

            // Send [0x03, addrH, addrM, addrL] + N dummy 0xFF bytes.
            // 0xFF (not 0x00) is the convention — keeps the bus idle-high.
            val sendBuf = byteArrayOf(0x03.toByte(), addrHigh, addrMid, addrLow) +
                          ByteArray(length) { 0xFF.toByte() }
            val recvBuf = ByteArray(sendBuf.size)
            if (!spiTransfer(device, sendBuf, recvBuf)) return null

            // First 4 bytes were clocked in during cmd+addr; data starts at 4.
            recvBuf.copyOfRange(4, sendBuf.size)
        } catch (e: CH341LibException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Backs up the entire flash to the provided output stream.
     * Detects the chip's real size from the JEDEC ID's capacity byte.
     */
    fun backupFullFlash(device: UsbDevice, outputStream: java.io.OutputStream): Boolean {
        val jedecId = detectSpiChip(device) ?: return false
        val capacityCode = jedecId.substring(4, 6).toInt(16)
        val totalSize = JEDEC_CAPACITY_BYTES[capacityCode] ?: return false
        return try {
            val chunkSize = 4096
            var i = 0L
            while (i < totalSize) {
                val length = minOf((totalSize - i).toInt(), chunkSize)
                val data = readSpiFlash(device, i, length) ?: return false
                outputStream.write(data)
                i += length
            }
            true
        } catch (e: CH341LibException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Writes bytes to SPI flash, splitting across page boundaries.
     * Page Program is limited to 256 bytes per command and must not
     * cross a 256-byte page boundary (the chip would wrap and corrupt
     * adjacent pages silently).
     */
    fun writeSpiFlash(device: UsbDevice, address: Long, data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        val pageOffset = (address % PAGE_SIZE).toInt()
        val bytesToPageEnd = PAGE_SIZE - pageOffset
        val firstChunk = minOf(data.size, bytesToPageEnd)
        if (!writeOnePage(device, address, data.copyOfRange(0, firstChunk))) return false
        return writeSpiFlash(device, address + firstChunk, data.copyOfRange(firstChunk, data.size))
    }

    private fun writeOnePage(device: UsbDevice, address: Long, data: ByteArray): Boolean {
        if (data.isEmpty() || data.size > PAGE_SIZE) return false
        if (!handOffToVendor(device)) return false
        return try {
            val mgr = CH341Manager.getInstance()
            mgr.CH34xSetParaMode(device, SPI_PARA_MODE)

            // 1. Write Enable (0x06)
            val weBuf = byteArrayOf(0x06.toByte())
            val weRecv = ByteArray(1)
            if (!spiTransfer(device, weBuf, weRecv)) return false

            // 2. Page Program (0x02) + 24-bit address + up to 256 bytes
            val addrHigh = ((address shr 16) and 0xFF).toByte()
            val addrMid = ((address shr 8) and 0xFF).toByte()
            val addrLow = (address and 0xFF).toByte()
            val sendBuf = byteArrayOf(0x02.toByte(), addrHigh, addrMid, addrLow) + data
            val recvBuf = ByteArray(sendBuf.size)
            spiTransfer(device, sendBuf, recvBuf)
        } catch (e: Exception) {
            false
        }
    }

    fun eraseSpiFlash(device: UsbDevice, address: Long): Boolean {
        if (!handOffToVendor(device)) return false
        return try {
            val mgr = CH341Manager.getInstance()
            mgr.CH34xSetParaMode(device, SPI_PARA_MODE)

            // 1. Write Enable (0x06)
            val weBuf = byteArrayOf(0x06.toByte())
            val weRecv = ByteArray(1)
            if (!spiTransfer(device, weBuf, weRecv)) return false

            // 2. Sector Erase (0x20) - 4KB aligned
            val addrHigh = ((address shr 16) and 0xFF).toByte()
            val addrMid = ((address shr 8) and 0xFF).toByte()
            val addrLow = (address and 0xFF).toByte()
            val sendBuf = byteArrayOf(0x20.toByte(), addrHigh, addrMid, addrLow)
            val recvBuf = ByteArray(sendBuf.size)
            spiTransfer(device, sendBuf, recvBuf)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Single-shot full-duplex SPI transfer.
     *
     * Wraps `CH34xStreamSPI5(UsbDevice, int iChipSelect, int iLength, byte[] send, byte[] recv)`.
     *
     * Two prior bugs were bundled into a single call with `0, 0` for the two `int`
     * parameters: the second `0` is `iLength` (transfer length in bytes), not a "speed"
     * setting — with `iLength=0` the static SPI engine moves zero bytes and the recv
     * buffer is never populated. The first `0` is `iChipSelect`; per the WCH header
     * `0` means "ignore CS" (no chip selected). The correct value for CS0 is `0x80`
     * (bit 7 = CS enable, bits 0-1 = D0/D1/D2 pin number).
     */
    private fun spiTransfer(device: UsbDevice, sendBuf: ByteArray, recvBuf: ByteArray): Boolean {
        require(sendBuf.size <= recvBuf.size) {
            "sendBuf (${sendBuf.size}) must fit in recvBuf (${recvBuf.size})"
        }
        return try {
            val mgr = CH341Manager.getInstance()
            // CS0 on D0 pin (the typical BIOS clip location), length = bytes to clock out.
            mgr.CH34xStreamSPI5(device, CS0, sendBuf.size, sendBuf, recvBuf)
        } catch (e: CH341LibException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        // iChipSelect encoding per WCH header: bit 7 = CS enable,
        // bits 0-1 = D0/D1/D2 pin. 0x80 = CS0 on D0 (BIOS clip default).
        private const val CS0 = 0x80

        // SPI para-mode value. The JAR accepts 0..2; 0x01 (current) and 0x02
        // (canonical WCH SDK value) are both valid SPI modes. Keep 0x01 to
        // match the prior working baseline.
        private const val SPI_PARA_MODE = 0x01

        // Standard SPI flash geometry.
        private const val PAGE_SIZE = 256
        private const val SECTOR_SIZE = 4096

        // JEDEC capacity byte → total flash size in bytes.
        // Winbond/Macronix/GigaDevice convention: capacity_code N → 2^N bits → 2^(N-3) bytes.
        // Common values: 0x14=128KB, 0x15=256KB, 0x16=512KB, 0x17=1MB, 0x18=2MB,
        // 0x19=4MB, 0x1A=8MB, 0x1B=16MB, 0x1C=32MB.
        private val JEDEC_CAPACITY_BYTES = mapOf(
            0x14 to 128L * 1024,
            0x15 to 256L * 1024,
            0x16 to 512L * 1024,
            0x17 to 1L * 1024 * 1024,
            0x18 to 2L * 1024 * 1024,
            0x19 to 4L * 1024 * 1024,
            0x1A to 8L * 1024 * 1024,
            0x1B to 16L * 1024 * 1024,
            0x1C to 32L * 1024 * 1024
        )
    }
}
