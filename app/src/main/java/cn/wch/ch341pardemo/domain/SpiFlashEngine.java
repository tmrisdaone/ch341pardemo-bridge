package cn.wch.ch341pardemo.domain;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import cn.wch.ch341lib.CH341Manager;
import cn.wch.ch341pardemo.domain.SpiFlashProtocol.FlashChip;

import java.util.Arrays;

/**
 * Core SPI flash programming engine for CH341A/CH347.
 * <p>
 * Supports reading, writing, erasing, and verifying 25xx-series SPI NOR flash
 * chips (Winbond, Macronix, GigaDevice, etc.) typically used for BIOS/UEFI.
 * <p>
 * Architecture:
 * <ol>
 *   <li>Opens the USB device and initializes for SPI mode</li>
 *   <li>Detects connected SPI flash chip via RDID command</li>
 *   <li>Provides high-level read/write/erase/verify operations</li>
 *   <li>Reports progress via callback for UI updates</li>
 * </ol>
 */
public class SpiFlashEngine {

    private static final String TAG = "SpiFlashEngine";

    // USB endpoints for CH341A
    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint epOut;
    private UsbEndpoint epIn;
    private UsbDevice device;

    // CH341 manager (for permission checking)
    private CH341Manager ch341Manager;

    // Detected flash chip
    private FlashChip detectedChip;
    private boolean spiModeInitialized = false;
    private boolean isCh347Device = false; // CH347 has native SPI
    private boolean engineOpen = false; // guards against double-init

    // Constants
    private static final int CTRL_TIMEOUT = 2000;
    private static final int BULK_TIMEOUT = 5000;
    private static final int SPI_CLOCK_HZ = 1_000_000; // 1MHz for reliable operation

    // CH341A SPI control transfer values
    private static final int REQ_TYPE_OUT = 0x40;
    private static final int REQ_CH341 = 0xA1;
    private static final int REQ_INIT = 0x9A;
    private static final int VAL_INIT = 0x1312;
    private static final int VAL_CS_LOW = 0x005F;
    private static final int VAL_CS_HIGH = 0x005E;

    // ── Progress callback ───────────────────────────────────────
    public interface ProgressCallback {
        void onProgress(String stage, int current, int total);
        void onMessage(String message);
        void onError(String error);
    }

    // ── Initialization ──────────────────────────────────────────────

    /**
     * Initialize the SPI flash engine. Opens the USB device, claims the
     * interface, and sends the CH341A SPI init sequence.
     *
     * @return true if initialization succeeded
     */
    public boolean init(UsbDevice device, UsbManager usbManager, CH341Manager manager) {
        // Guard against double-init connection leak
        if (engineOpen) {
            Log.w(TAG, "Engine already open, closing first");
            close();
        }

        this.device = device;
        this.ch341Manager = manager;

        // Check if it's a CH347 (native SPI) or CH341A (bit-bang)
        isCh347Device = Ch341UsbIds.isCh347(device);

        try {
            connection = usbManager.openDevice(device);
            if (connection == null) {
                Log.e(TAG, "Failed to open USB device");
                return false;
            }

            if (!findAndClaimInterface()) {
                Log.e(TAG, "Failed to claim USB interface");
                cleanup();
                return false;
            }

            if (isCh347Device) {
                initCh347Spi();
            } else {
                initCh341aSpi();
            }

            spiModeInitialized = true;
            engineOpen = true;
            Log.i(TAG, "SPI engine initialized (" +
                    (isCh347Device ? "CH347 native" : "CH341A bit-bang") + ")");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Init failed", e);
            cleanup();
            return false;
        }
    }

    /**
     * Detect the connected SPI flash chip by sending RDID command.
     *
     * @return detected chip info, or null if unrecognized
     */
    public FlashChip detectChip() {
        if (!spiModeInitialized) return null;

        byte[] rdid = spiTransfer(new byte[]{SpiFlashProtocol.CMD_RDID}, 3);
        if (rdid == null || rdid.length < 2) {
            Log.w(TAG, "RDID failed, no response from flash chip");
            return null;
        }

        int mfrId = rdid[0] & 0xFF;
        int devId = (rdid.length >= 3) ? ((rdid[1] & 0xFF) << 8) | (rdid[2] & 0xFF) : (rdid[1] & 0xFF);

        Log.i(TAG, String.format("RDID: MFR=0x%02X (%s), DEV=0x%04X",
                mfrId, SpiFlashProtocol.getManufacturerName(mfrId), devId));

        detectedChip = SpiFlashProtocol.identify(mfrId, devId);
        if (detectedChip == null) {
            Log.w(TAG, "Unknown flash chip. You can still read/erase but size must be manual.");
        } else {
            Log.i(TAG, "Detected: " + detectedChip.name + " (" + detectedChip.description + ")");
        }

        return detectedChip;
    }

    /**
     * @return the currently detected chip, or null
     */
    public FlashChip getDetectedChip() {
        return detectedChip;
    }

    /**
     * @return whether the engine is initialized and ready
     */
    public boolean isReady() {
        return spiModeInitialized && connection != null;
    }

    /**
     * Clean up and close the USB connection.
     */
    public void close() {
        spiModeInitialized = false;
        engineOpen = false;
        detectedChip = null;
        cleanup();
        Log.i(TAG, "SPI engine closed");
    }

    // ── High-Level Flash Operations ─────────────────────────────────

    /**
     * Read the flash status register.
     */
    public int readStatus() {
        byte[] result = spiTransfer(new byte[]{SpiFlashProtocol.CMD_RDSR}, 1);
        return (result != null && result.length > 0) ? (result[0] & 0xFF) : -1;
    }

    /**
     * Wait for the flash chip to finish an operation (WIP bit clears).
     */
    public void waitWhileBusy(ProgressCallback callback) throws InterruptedException {
        int timeout = isCh347Device ? 60000 : 120000; // longer for CH341A bit-bang
        int waited = 0;
        while (waited < timeout) {
            int sr = readStatus();
            if (sr < 0) {
                callback.onError("Failed to read status register");
                return;
            }
            if ((sr & SpiFlashProtocol.SR_BUSY) == 0) return;
            // Stepped polling: 1ms for the first 100ms, 10ms up to 1s, then 100ms.
            int delay = waited < 100 ? 1 : (waited < 1000 ? 10 : 100);
            Thread.sleep(delay);
            waited += delay;
        }
        callback.onError("Timeout waiting for flash ready");
    }

    /**
     * Send Write Enable command. Returns true only if the SPI transfer itself
     * succeeded <b>and</b> the WEL latch is now set in the status register —
     * which is the real proof the chip accepted it (a protected/locked chip
     * will keep WEL=0 and silently ignore subsequent program/erase commands).
     */
    public boolean writeEnable() {
        // WREN has no data phase; a null result means the USB transfer failed.
        if (spiTransfer(new byte[]{SpiFlashProtocol.CMD_WREN}, 0) == null) return false;
        int sr = readStatus();
        return sr >= 0 && (sr & SpiFlashProtocol.SR_WEL) != 0;
    }

    /**
     * Clear the Block-Protect bits in the status register so erase/program
     * can actually modify flash. Many chips ship with BP0..BP2 set, which
     * makes those operations silently no-op. This is opt-in — call once
     * before a write/erase if your chip is known to be protected.
     *
     * <p>Requires Write Enable first (we issue WREN internally).
     *
     * @return true if WRSR was issued and BP bits read back as 0.
     */
    public boolean clearBlockProtection() {
        if (!writeEnable()) return false;
        int sr = readStatus();
        if (sr < 0) return false;
        // Mask off BP0/BP1/BP2 and the hold of them via SRP (best effort).
        int cleared = sr & ~(SpiFlashProtocol.SR_BP0 | SpiFlashProtocol.SR_BP1
                | SpiFlashProtocol.SR_BP2);
        if (spiTransfer(new byte[]{SpiFlashProtocol.CMD_WRSR, (byte) cleared}, 0) == null) {
            return false;
        }
        // Give the chip a moment and re-read.
        try { waitWhileBusy(new ProgressCallback() {
            @Override public void onProgress(String s, int c, int t) {}
            @Override public void onMessage(String m) {}
            @Override public void onError(String e) {}
        }); } catch (InterruptedException ignored) { return false; }
        int after = readStatus();
        return after >= 0 && (after & (SpiFlashProtocol.SR_BP0
                | SpiFlashProtocol.SR_BP1 | SpiFlashProtocol.SR_BP2)) == 0;
    }

    /**
     * Read flash contents starting at address into buffer.
     *
     * @param address  starting address (0 to chip size)
     * @param buffer   destination buffer
     * @param offset   offset into buffer to start writing
     * @param length   number of bytes to read
     * @param callback progress reporter
     */
    public boolean read(int address, byte[] buffer, int offset, int length,
                        ProgressCallback callback) throws InterruptedException {
        if (!spiModeInitialized || connection == null) {
            callback.onError("Engine not initialized");
            return false;
        }

        int chunkSize = Math.min(4096, length);
        boolean largeChip = (detectedChip != null && detectedChip.sizeBytes > (1 << 24)); // >16MB needs 4-byte addressing
        int bytesRead = 0;

        while (bytesRead < length) {
            int chunk = Math.min(chunkSize, length - bytesRead);
            int addr = address + bytesRead;

            // Build READ command: 0x03 + 3 or 4 byte address
            byte[] cmd = largeChip ? new byte[5] : new byte[4];
            cmd[0] = SpiFlashProtocol.CMD_READ;
            if (largeChip) {
                cmd[1] = (byte) ((addr >> 24) & 0xFF);
                cmd[2] = (byte) ((addr >> 16) & 0xFF);
                cmd[3] = (byte) ((addr >> 8) & 0xFF);
                cmd[4] = (byte) (addr & 0xFF);
            } else {
                cmd[1] = (byte) ((addr >> 16) & 0xFF);
                cmd[2] = (byte) ((addr >> 8) & 0xFF);
                cmd[3] = (byte) (addr & 0xFF);
            }

            byte[] result = spiTransfer(cmd, chunk);
            if (result == null || result.length == 0) {
                callback.onError("Read failed at 0x" + Integer.toHexString(addr));
                return false;
            }

            // Defensive: never let a short/over read run past the destination buffer.
            int copyLen = Math.min(Math.min(result.length, chunk),
                    buffer.length - (offset + bytesRead));
            if (copyLen <= 0) {
                callback.onError("Read buffer overflow at 0x" + Integer.toHexString(addr));
                return false;
            }
            System.arraycopy(result, 0, buffer, offset + bytesRead, copyLen);
            bytesRead += copyLen;

            callback.onProgress("Reading", bytesRead, length);
        }

        return true;
    }

    /**
     * Program (write) data to flash. Flash must be erased first.
     *
     * @param address  starting address (must be page-aligned for best results)
     * @param data     data to write
     * @param offset   offset into data array
     * @param length   number of bytes to write
     * @param callback progress reporter
     */
    public boolean program(int address, byte[] data, int offset, int length,
                           ProgressCallback callback) throws InterruptedException {
        if (!spiModeInitialized || connection == null) {
            callback.onError("Engine not initialized");
            return false;
        }

        int pageSize = SpiFlashProtocol.PAGE_SIZE; // 256 bytes
        int bytesWritten = 0;
        boolean largeChip = (detectedChip != null && detectedChip.sizeBytes > (1 << 24));

        while (bytesWritten < length) {
            int addr = address + bytesWritten;

            // Compute chunk that DOES NOT cross a 256-byte page boundary
            int pageOffset = addr & (pageSize - 1); // offset within current page
            int chunk = Math.min(pageSize - pageOffset, length - bytesWritten);
            chunk = Math.min(chunk, length - bytesWritten);

            // Write Enable before each program operation
            if (!writeEnable()) {
                callback.onError("WREN failed at 0x" + Integer.toHexString(addr));
                return false;
            }

            // Build Page Program command: 0x02 + 3/4-byte address + data
            byte[] cmd = largeChip ? new byte[5 + chunk] : new byte[4 + chunk];
            cmd[0] = SpiFlashProtocol.CMD_PP;
            if (largeChip) {
                cmd[1] = (byte) ((addr >> 24) & 0xFF);
                cmd[2] = (byte) ((addr >> 16) & 0xFF);
                cmd[3] = (byte) ((addr >> 8) & 0xFF);
                cmd[4] = (byte) (addr & 0xFF);
                System.arraycopy(data, offset + bytesWritten, cmd, 5, chunk);
            } else {
                cmd[1] = (byte) ((addr >> 16) & 0xFF);
                cmd[2] = (byte) ((addr >> 8) & 0xFF);
                cmd[3] = (byte) (addr & 0xFF);
                System.arraycopy(data, offset + bytesWritten, cmd, 4, chunk);
            }

            byte[] result = spiTransfer(cmd, 0);
            if (result == null) {
                callback.onError("Program failed at 0x" + Integer.toHexString(addr));
                return false;
            }

            // Wait for write to complete
            waitWhileBusy(callback);

            bytesWritten += chunk;
            callback.onProgress("Writing", bytesWritten, length);
        }

        return true;
    }

    /**
     * Erase a 4KB sector.
     */
    public boolean sectorErase(int address, ProgressCallback callback) throws InterruptedException {
        if (!writeEnable()) return false;
        byte[] cmd = new byte[4];
        cmd[0] = SpiFlashProtocol.CMD_SE;
        cmd[1] = (byte) ((address >> 16) & 0xFF);
        cmd[2] = (byte) ((address >> 8) & 0xFF);
        cmd[3] = (byte) (address & 0xFF);
        byte[] result = spiTransfer(cmd, 0);
        waitWhileBusy(callback);
        return result != null;
    }

    /**
     * Erase a 64KB block.
     */
    public boolean blockErase64k(int address, ProgressCallback callback) throws InterruptedException {
        if (!writeEnable()) return false;
        byte[] cmd = new byte[4];
        cmd[0] = SpiFlashProtocol.CMD_BE_64K;
        cmd[1] = (byte) ((address >> 16) & 0xFF);
        cmd[2] = (byte) ((address >> 8) & 0xFF);
        cmd[3] = (byte) (address & 0xFF);
        byte[] result = spiTransfer(cmd, 0);
        waitWhileBusy(callback);
        return result != null;
    }

    /**
     * Erase the entire chip.
     */
    public boolean chipErase(ProgressCallback callback) throws InterruptedException {
        if (!writeEnable()) return false;
        spiTransfer(new byte[]{SpiFlashProtocol.CMD_CE}, 0);
        // Chip erase takes a while
        callback.onMessage("Chip erase initiated, waiting...");
        waitWhileBusy(callback);
        return true;
    }

    /**
     * Erase the specified range. Uses the most efficient erase size:
     * 64KB blocks for large areas, 4KB sectors for small/specific areas.
     */
    public boolean eraseRange(int address, int length, ProgressCallback callback) throws InterruptedException {
        callback.onMessage("Erasing range 0x" + Integer.toHexString(address)
                + " - 0x" + Integer.toHexString((long) address + length));

        // If erasing the whole chip, use chip erase
        if (detectedChip != null && address == 0 && length >= detectedChip.sizeBytes) {
            return chipErase(callback);
        }

        // Drive erase geometry from the detected chip's own erase sector size,
        // not a hardcoded 4KiB assumption. Some parts (Spansion S25FL, GD25Q*)
        // only reliably support 0xD8 (64KiB) and treat 0x20 (4KiB) as either
        // unsupported or a different erase size.
        int minErase = (detectedChip != null) ? detectedChip.sectorSize : SpiFlashProtocol.SECTOR_SIZE_4K;
        boolean canErase4k = (minErase <= SpiFlashProtocol.SECTOR_SIZE_4K);
        int block64k = SpiFlashProtocol.BLOCK_SIZE_64K;
        int block4k = SpiFlashProtocol.SECTOR_SIZE_4K;

        int erased = 0;
        while (erased < length) {
            int addr = address + erased;
            int remaining = length - erased;

            if (remaining >= block64k && (addr % block64k) == 0) {
                if (!blockErase64k(addr, callback)) {
                    callback.onError("64KiB block erase failed at 0x" + Integer.toHexString(addr));
                    return false;
                }
                erased += block64k;
            } else if (canErase4k && remaining >= block4k && (addr % block4k) == 0) {
                if (!sectorErase(addr, callback)) {
                    callback.onError("4KiB sector erase failed at 0x" + Integer.toHexString(addr));
                    return false;
                }
                erased += block4k;
            } else if (canErase4k) {
                // Unaligned remainder: erase one 4KiB sector (sub-sector erase).
                if (!sectorErase(addr, callback)) {
                    callback.onError("4KiB sector erase failed at 0x" + Integer.toHexString(addr));
                    return false;
                }
                erased += block4k;
            } else {
                // Chip only supports 64KiB erase and we have a non-aligned tail.
                // Erase the containing 64KiB block (over-erasing past the requested
                // end is harmless — the caller asked to erase <this> region).
                int blockBase = addr - (addr % block64k);
                if (!blockErase64k(blockBase, callback)) {
                    callback.onError("64KiB block erase failed at 0x" + Integer.toHexString(blockBase));
                    return false;
                }
                erased = Math.max(erased + 1, (blockBase + block64k) - address);
            }
            callback.onProgress("Erasing", Math.min(erased, length), length);
        }

        return true;
    }

    /**
     * Verify that the flash contents match the given buffer.
     */
    public boolean verify(int address, byte[] data, int offset, int length,
                          ProgressCallback callback) throws InterruptedException {
        byte[] readback = new byte[length];
        boolean ok = read(address, readback, 0, length, callback);
        if (!ok) return false;

        for (int i = 0; i < length; i++) {
            if (readback[i] != data[offset + i]) {
                callback.onError("Verify failed at offset 0x"
                        + Integer.toHexString(address + i)
                        + ": expected 0x" + Integer.toHexString(data[offset + i] & 0xFF)
                        + ", got 0x" + Integer.toHexString(readback[i] & 0xFF));
                return false;
            }
            if (i % 4096 == 0) {
                callback.onProgress("Verifying", i, length);
            }
        }

        callback.onProgress("Verifying", length, length);
        callback.onMessage("Verify: " + length + " bytes OK");
        return true;
    }

    // ── Low-Level SPI Transfers ─────────────────────────────────────

    /**
     * Perform an SPI transaction on the CH341A.
     * <p>
     * For CH341A: Pulls CS low, bulk-writes the command, bulk-reads the
     * response, then pulls CS high.
     * For CH347: Uses native SPI WriteRead.
     */
    public byte[] spiTransfer(byte[] txData, int rxLength) {
        if (connection == null || epOut == null) {
            Log.e(TAG, "USB not connected");
            return null;
        }

        try {
            if (isCh347Device) {
                return spiTransferCh347(txData, rxLength);
            } else {
                return spiTransferCh341(txData, rxLength);
            }
        } catch (Exception e) {
            Log.e(TAG, "SPI transfer error", e);
            return null;
        }
    }

    /**
     * CH341A SPI transfer using CS control + bulk endpoints.
     */
    private byte[] spiTransferCh341(byte[] txData, int rxLength) {
        // CS low
        int ret = connection.controlTransfer(
                REQ_TYPE_OUT, REQ_CH341, VAL_CS_LOW, 0x0000, null, 0, CTRL_TIMEOUT);
        if (ret < 0) {
            Log.e(TAG, "CS low failed");
            return null;
        }

        // CH341A CS settle time is sub-microsecond; the control transfer itself
        // provides sufficient delay. A Thread.sleep(1) here just wastes ~1ms per
        // transaction (Linux granularity is ~1ms). Removed.

        // Bulk write
        int written = connection.bulkTransfer(epOut, txData, txData.length, BULK_TIMEOUT);
        if (written < 0) {
            Log.e(TAG, "Bulk write failed: " + written);
            // Still try to set CS high before returning
            connection.controlTransfer(REQ_TYPE_OUT, REQ_CH341, VAL_CS_HIGH, 0x0000, null, 0, CTRL_TIMEOUT);
            return null;
        }

        byte[] result;
        if (rxLength > 0) {
            // Bulk read
            byte[] buffer = new byte[rxLength];
            int read = connection.bulkTransfer(epIn, buffer, rxLength, BULK_TIMEOUT);
            if (read < 0) {
                Log.e(TAG, "Bulk read failed: " + read);
                connection.controlTransfer(REQ_TYPE_OUT, REQ_CH341, VAL_CS_HIGH, 0x0000, null, 0, CTRL_TIMEOUT);
                return null;
            }
            result = Arrays.copyOf(buffer, Math.min(read, rxLength));
        } else {
            result = new byte[0];
        }

        // CS high
        connection.controlTransfer(REQ_TYPE_OUT, REQ_CH341, VAL_CS_HIGH, 0x0000, null, 0, CTRL_TIMEOUT);

        return result;
    }

    /**
     * CH347 SPI transfer using native SPI WriteRead (not yet implemented).
     * <p>
     * The CH347 has native SPI command framing that is different from the
     * CH341A bit-bang. This fallback uses raw bulk endpoints which will NOT
     * speak the correct CH347 SPI protocol. Currently a placeholder — flash
     * operations on CH347 will likely fail until the native CH347Manager
     * SPI methods are wired in.
     */
    private byte[] spiTransferCh347(byte[] txData, int rxLength) {
        // For CH347, we use the CH347Manager's native SPI methods
        // Fall back to bulk transfer if possible
        byte[] buffer = new byte[Math.max(txData.length, rxLength)];
        System.arraycopy(txData, 0, buffer, 0, txData.length);

        int written = connection.bulkTransfer(epOut, buffer, buffer.length, BULK_TIMEOUT);
        if (written < 0) return null;

        if (rxLength > 0) {
            byte[] readBuf = new byte[rxLength];
            int read = connection.bulkTransfer(epIn, readBuf, rxLength, BULK_TIMEOUT);
            if (read < 0) return null;
            return Arrays.copyOf(readBuf, read);
        }
        return new byte[0];
    }

    // ── USB Setup ───────────────────────────────────────────────────

    private boolean findAndClaimInterface() {
        if (device == null || connection == null) return false;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            int cls = intf.getInterfaceClass();

            // CH341A uses vendor-specific class (0xFF)
            if (cls == UsbConstants.USB_CLASS_VENDOR_SPEC || cls == 0xFF) {
                if (connection.claimInterface(intf, true)) {
                    usbInterface = intf;
                    // Find bulk endpoints
                    for (int j = 0; j < intf.getEndpointCount(); j++) {
                        UsbEndpoint ep = intf.getEndpoint(j);
                        if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                                epIn = ep;
                            } else if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                                epOut = ep;
                            }
                        }
                    }
                    if (epIn != null && epOut != null) return true;
                    connection.releaseInterface(intf);
                }
            }
        }
        return false;
    }

    private void initCh341aSpi() {
        if (connection == null) return;

        Log.i(TAG, "Initializing CH341A for SPI mode...");

        // CH341 init sequence for SPI mode
        connection.controlTransfer(0x40, 0xA1, 0x0000, 0x0000, null, 0, CTRL_TIMEOUT);
        sleep(10);
        connection.controlTransfer(0x40, 0x9A, 0x1312, 0x0000, null, 0, CTRL_TIMEOUT);
        sleep(10);
        connection.controlTransfer(0x40, 0xA1, 0x0000, 0x0000, null, 0, CTRL_TIMEOUT);
        sleep(10);

        Log.i(TAG, "CH341A SPI init complete");
    }

    private void initCh347Spi() {
        // CH347 has native SPI - init is handled at open time
        Log.i(TAG, "CH347 device detected - native SPI mode");
    }

    private void cleanup() {
        if (connection == null) return;
        try {
            if (usbInterface != null) {
                connection.releaseInterface(usbInterface);
            }
        } catch (Exception ignored) {}
        try {
            connection.close();
        } catch (Exception ignored) {}
        usbInterface = null;
        epIn = null;
        epOut = null;
        connection = null;
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
