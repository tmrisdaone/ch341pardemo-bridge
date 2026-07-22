package cn.wch.ch341pardemo.data

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cn.wch.ch341lib.CH341Manager
import cn.wch.ch341lib.exception.CH341LibException
import cn.wch.ch347lib.exception.NoPermissionException
import cn.wch.ch347lib.exception.ChipException
import cn.wch.ch341pardemo.domain.Ch341UsbIds

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
    /** True only for the PIDs this app actually knows how to drive. */
    val isSupportedCh341: Boolean get() =
        Ch341UsbIds.isSupportedCh341ById(vendorId, productId)
}

/**
 * Kept for backwards compatibility with code that builds a Set<Int>. Delegates
 * to [Ch341UsbIds] so there is a single source of truth for the PID list.
 */
object Ch341VidPid {
    val VID: Int get() = Ch341UsbIds.VID_QINHENG
    val PIDS: Set<Int> get() = Ch341UsbIds.SUPPORTED_PIDS
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
        listDevices().filter { it.isSupportedCh341 }

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
     * Returns the chip ID as a hex string or null if detection fails.
     */
    fun detectSpiChip(device: UsbDevice): String? {
        if (!handOffToVendor(device)) return null
        return try {
            val mgr = CH341Manager.getInstance()
            mgr.CH34xSetParaMode(device, 0x01)
            val sendBuf = byteArrayOf(0x9F.toByte())
            val recvBuf = ByteArray(3) // JEDEC ID is 3 bytes
            val success = mgr.CH34xStreamSPI5(device, 0, 0, sendBuf, recvBuf)
            if (!success) return null
            recvBuf.joinToString("") { "%02X".format(it) }
        } catch (e: CH341LibException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads a block of data from the SPI flash.
     * Supports 4-byte addressing for chips >16MiB.
     */
    fun readSpiFlash(device: UsbDevice, address: Long, length: Int): ByteArray? {
        if (!handOffToVendor(device)) return null
        return try {
            val mgr = CH341Manager.getInstance()
            mgr.CH34xSetParaMode(device, 0x01)

            // Enter 4-byte address mode if address >= 16MiB
            if (address >= 0x1000000) {
                val en4b = byteArrayOf(0xB7.toByte())
                val en4bRecv = ByteArray(1)
                mgr.CH34xStreamSPI5(device, 0, 0, en4b, en4bRecv)
            }

            val use4Byte = address >= 0x1000000
            val sendBuf: ByteArray
            if (use4Byte) {
                val addrBytes = ByteArray(5)
                addrBytes[0] = 0x03.toByte() // READ command
                addrBytes[1] = ((address shr 24) and 0xFF).toByte()
                addrBytes[2] = ((address shr 16) and 0xFF).toByte()
                addrBytes[3] = ((address shr 8) and 0xFF).toByte()
                addrBytes[4] = (address and 0xFF).toByte()
                sendBuf = addrBytes + ByteArray(length) { 0 }
            } else {
                val addrHigh = ((address shr 16) and 0xFF).toByte()
                val addrMid = ((address shr 8) and 0xFF).toByte()
                val addrLow = (address and 0xFF).toByte()
                sendBuf = byteArrayOf(0x03.toByte(), addrHigh, addrMid, addrLow) + ByteArray(length) { 0 }
            }

            val recvBuf = ByteArray(sendBuf.size)
            val success = mgr.CH34xStreamSPI5(device, 0, 0, sendBuf, recvBuf)

            if (!success || recvBuf.size < sendBuf.size) return null

            // The first N bytes of the response are received while sending the command
            recvBuf.copyOfRange(sendBuf.size - length, recvBuf.size)
        } catch (e: CH341LibException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Backs up the entire flash to the provided output stream.
     * Attempts to detect chip size via RDID; falls back to 16MiB.
     */
    fun backupFullFlash(device: UsbDevice, outputStream: java.io.OutputStream): Boolean {
        if (!handOffToVendor(device)) return false
        return try {
            val chipId = detectSpiChip(device)
            val totalSize = when (chipId) {
                null -> 16 * 1024 * 1024 // fallback
                else -> parseChipSize(chipId)
            }
            val chunkSize = 4096
            for (i in 0 until totalSize step chunkSize) {
                val length = minOf(chunkSize, totalSize - i)
                val data = readSpiFlash(device, i.toLong(), length) ?: return false
                outputStream.write(data)
            }
            true
        } catch (e: CH341LibException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun parseChipSize(chipId: String): Int {
        // chipId format: "MFxxxx" where xxxx is device ID
        // Extract device ID bytes and match against known sizes
        val devId = chipId.substring(2).toIntOrNull(16) ?: return 16 * 1024 * 1024
        return when (devId) {
            0x13 -> 1 * 1024 * 1024    // 8 Mbit
            0x14 -> 2 * 1024 * 1024    // 16 Mbit
            0x15 -> 4 * 1024 * 1024    // 32 Mbit
            0x16 -> 8 * 1024 * 1024    // 64 Mbit
            0x17 -> 16 * 1024 * 1024   // 128 Mbit
            0x18 -> 32 * 1024 * 1024   // 256 Mbit
            0x19 -> 64 * 1024 * 1024   // 512 Mbit
            else -> 16 * 1024 * 1024
        }
    }

    fun writeSpiFlash(device: UsbDevice, address: Long, data: ByteArray): Boolean {
        if (!handOffToVendor(device)) return false
        return try {
            val mgr = CH341Manager.getInstance()
            mgr.CH34xSetParaMode(device, 0x01)

            // Enter 4-byte address mode if address >= 16MiB
            val use4Byte = address >= 0x1000000
            if (use4Byte) {
                val en4b = byteArrayOf(0xB7.toByte())
                val en4bRecv = ByteArray(1)
                mgr.CH34xStreamSPI5(device, 0, 0, en4b, en4bRecv)
            }

            // 1. Write Enable (0x06)
            val weBuf = byteArrayOf(0x06.toByte())
            val weRecv = ByteArray(1)
            if (!mgr.CH34xStreamSPI5(device, 0, 0, weBuf, weRecv)) return false

            // 2. Page Program (0x02)
            val sendBuf: ByteArray
            if (use4Byte) {
                val addrBytes = ByteArray(5)
                addrBytes[0] = 0x02.toByte() // PP command
                addrBytes[1] = ((address shr 24) and 0xFF).toByte()
                addrBytes[2] = ((address shr 16) and 0xFF).toByte()
                addrBytes[3] = ((address shr 8) and 0xFF).toByte()
                addrBytes[4] = (address and 0xFF).toByte()
                sendBuf = addrBytes + data
            } else {
                val addrHigh = ((address shr 16) and 0xFF).toByte()
                val addrMid = ((address shr 8) and 0xFF).toByte()
                val addrLow = (address and 0xFF).toByte()
                sendBuf = byteArrayOf(0x02.toByte(), addrHigh, addrMid, addrLow) + data
            }
            val recvBuf = ByteArray(sendBuf.size)
            mgr.CH34xStreamSPI5(device, 0, 0, sendBuf, recvBuf)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun eraseSpiFlash(device: UsbDevice, address: Long): Boolean {
        if (!handOffToVendor(device)) return false
        return try {
            val mgr = CH341Manager.getInstance()
            mgr.CH34xSetParaMode(device, 0x01)

            // Enter 4-byte address mode if address >= 16MiB
            val use4Byte = address >= 0x1000000
            if (use4Byte) {
                val en4b = byteArrayOf(0xB7.toByte())
                val en4bRecv = ByteArray(1)
                mgr.CH34xStreamSPI5(device, 0, 0, en4b, en4bRecv)
            }

            // 1. Write Enable (0x06)
            val weBuf = byteArrayOf(0x06.toByte())
            val weRecv = ByteArray(1)
            if (!mgr.CH34xStreamSPI5(device, 0, 0, weBuf, weRecv)) return false

            // 2. Sector Erase (0x20) - 4KB
            val sendBuf: ByteArray
            if (use4Byte) {
                val addrBytes = ByteArray(5)
                addrBytes[0] = 0x20.toByte() // SE command
                addrBytes[1] = ((address shr 24) and 0xFF).toByte()
                addrBytes[2] = ((address shr 16) and 0xFF).toByte()
                addrBytes[3] = ((address shr 8) and 0xFF).toByte()
                addrBytes[4] = (address and 0xFF).toByte()
                sendBuf = addrBytes
            } else {
                val addrHigh = ((address shr 16) and 0xFF).toByte()
                val addrMid = ((address shr 8) and 0xFF).toByte()
                val addrLow = (address and 0xFF).toByte()
                sendBuf = byteArrayOf(0x20.toByte(), addrHigh, addrMid, addrLow)
            }
            val recvBuf = ByteArray(sendBuf.size)
            mgr.CH34xStreamSPI5(device, 0, 0, sendBuf, recvBuf)
            true
        } catch (e: Exception) {
            false
        }
    }
}

