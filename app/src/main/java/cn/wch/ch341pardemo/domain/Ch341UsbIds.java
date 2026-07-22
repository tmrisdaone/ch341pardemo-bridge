package cn.wch.ch341pardemo.domain;

import android.hardware.usb.UsbDevice;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Single source of truth for CH341/CH347 VID/PID matching.
 *
 * <p>Both the Kotlin UI layer ({@code Ch341VidPid}) and the Java services
 * ({@code TermuxBridge}, {@code FlasherService}, {@code SpiFlashEngine})
 * must route device-matching through this class so the supported PID list
 * never drifts between files.
 *
 * <p>QinHeng/WCH makes many non-CH341 parts (CH340 UART, CH9124 Ethernet,
 * CH57x MCUs, etc.). The old "accept any 0x1A86 device" fallback would
 * happily attempt a raw USB claim on those, so it has been removed.
 */
public final class Ch341UsbIds {

    public static final int VID_QINHENG = 0x1A86;

    /**
     * PIDs this app knows how to drive either as a CH341A USB-parallel/SPI
     * bridge or as a CH347 multi-protocol chip with native SPI. Includes the
     * newer CH341 variants (0xE02x).
     */
    public static final Set<Integer> SUPPORTED_PIDS = new HashSet<>(Arrays.asList(
            0x7523, // CH341A
            0x5523, // CH341A (alt)
            0x7522, // CH341
            0x5512, // CH341 (alt)
            0x7584, // CH347
            0x7585, // CH347
            0x7586, // CH347
            0xE023, // newer CH341 variant
            0xE024, // newer CH341 variant
            0xE025  // newer CH341 variant
    ));

    private Ch341UsbIds() {}

    /**
     * Primitive form of [isSupportedCh341] for the Kotlin data layer that only
     * holds raw ints without an attached [UsbDevice].
     */
    public static boolean isSupportedCh341ById(int vendorId, int productId) {
        return vendorId == VID_QINHENG && SUPPORTED_PIDS.contains(productId);
    }

    /** Is this a QinHeng VID we should consider at all? */
    public static boolean isQinheng(UsbDevice d) {
        return d != null && d.getVendorId() == VID_QINHENG;
    }

    /**
     * True if this device is one the app explicitly supports driving as a
     * CH341/CH347 bridge. Unknown WCH devices are <b>rejected</b> so we don't
     * hand a CH340 UART dongle to the SPI engine.
     */
    public static boolean isSupportedCh341(UsbDevice d) {
        return d != null
                && d.getVendorId() == VID_QINHENG
                && SUPPORTED_PIDS.contains(d.getProductId());
    }

    /** True only for the CH347 family (native SPI). */
    public static boolean isCh347(UsbDevice d) {
        if (d == null) return false;
        int pid = d.getProductId();
        return d.getVendorId() == VID_QINHENG
                && (pid == 0x7584 || pid == 0x7585 || pid == 0x7586);
    }
}
