package cn.wch.ch341pardemo.data

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cn.wch.ch341lib.CH341Manager
import cn.wch.ch341lib.exception.CH341LibException
import cn.wch.ch347lib.exception.NoPermissionException
import cn.wch.ch347lib.exception.ChipException

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
}
