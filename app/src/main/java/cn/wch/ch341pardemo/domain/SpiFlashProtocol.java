package cn.wch.ch341pardemo.domain;

/**
 * SPI flash protocol constants, standard commands, and chip identification database.
 * <p>
 * Covers common 25xx-series SPI NOR flash chips used for BIOS/UEFI firmware
 * (Winbond, Macronix, GD, Puya, XMC, etc.).
 */
public final class SpiFlashProtocol {

    // ── Standard SPI Flash Commands ────────────────────────────────
    public static final byte CMD_WREN           = (byte) 0x06; // Write Enable
    public static final byte CMD_WRDI           = (byte) 0x04; // Write Disable
    public static final byte CMD_RDSR           = (byte) 0x05; // Read Status Register
    public static final byte CMD_WRSR           = (byte) 0x01; // Write Status Register
    public static final byte CMD_READ           = (byte) 0x03; // Read Data (normal, up to 33MHz)
    public static final byte CMD_FAST_READ      = (byte) 0x0B; // Fast Read (with dummy byte)
    public static final byte CMD_PP             = (byte) 0x02; // Page Program (max 256 bytes)
    public static final byte CMD_SE             = (byte) 0x20; // Sector Erase (4KB)
    public static final byte CMD_BE_32K         = (byte) 0x52; // Block Erase (32KB)
    public static final byte CMD_BE_64K         = (byte) 0xD8; // Block Erase (64KB)
    public static final byte CMD_CE             = (byte) 0xC7; // Chip Erase (also 0x60)
    public static final byte CMD_CE_ALT         = (byte) 0x60; // Chip Erase (alternative)
    public static final byte CMD_RDID           = (byte) 0x9F; // Read Identification
    public static final byte CMD_REMS           = (byte) 0x90; // Read Electronic Manufacturer & Device ID
    public static final byte CMD_DP             = (byte) 0xB9; // Deep Power Down
    public static final byte CMD_RDP            = (byte) 0xAB; // Release Deep Power Down
    public static final byte CMD_EN4B           = (byte) 0xB7; // Enter 4-byte address mode
    public static final byte CMD_EX4B           = (byte) 0xE9; // Exit 4-byte address mode
    public static final byte CMD_RSTEN          = (byte) 0x66; // Reset Enable
    public static final byte CMD_RST            = (byte) 0x99; // Reset
    public static final byte CMD_SRSTEN         = (byte) 0xAA; // Enable RSTI/O
    public static final byte CMD_SRST           = (byte) 0xF5; // Reset (via RSTI/O)

    // ── Status Register Bits ───────────────────────────────────────
    public static final byte SR_BUSY   = 0x01; // Write In Progress (WIP)
    public static final byte SR_WEL    = 0x02; // Write Enable Latch
    public static final byte SR_BP0    = 0x04; // Block Protect 0
    public static final byte SR_BP1    = 0x08; // Block Protect 1
    public static final byte SR_BP2    = 0x10; // Block Protect 2
    public static final byte SR_TB     = 0x20; // Top/Bottom protect
    public static final byte SR_SEC    = 0x40; // Sector protect
    public static final byte SR_SRP    = (byte) 0x80; // Status Register Protect

    // ── Page / Sector / Block Sizes ────────────────────────────────
    public static final int PAGE_SIZE      = 256;
    public static final int SECTOR_SIZE_4K = 4096;   // 4KB sector
    public static final int BLOCK_SIZE_32K = 32768;  // 32KB block
    public static final int BLOCK_SIZE_64K = 65536;  // 64KB block

    // ── CH341A USB Endpoints ───────────────────────────────────────
    public static final int CH341_EP_OUT = 0x02;   // Bulk OUT endpoint
    public static final int CH341_EP_IN  = 0x82;   // Bulk IN endpoint

    // ── Known Flash Chip Database ──────────────────────────────────
    public static final class FlashChip {
        public final int manufacturerId;
        public final int deviceId;
        public final int sizeBytes;       // total size in bytes
        public final int sectorSize;      // erase sector size
        public final String name;
        public final String description;

        public FlashChip(int mfr, int devId, int size, int sectSize, String name, String desc) {
            this.manufacturerId = mfr;
            this.deviceId = devId;
            this.sizeBytes = size;
            this.sectorSize = sectSize;
            this.name = name;
            this.description = desc;
        }

        public int getPageCount() { return sizeBytes / PAGE_SIZE; }
        public int getSectorCount() { return sizeBytes / sectorSize; }
    }

    // Full chip database: manufacturer ID → device ID → chip info
    private static final java.util.HashMap<Integer, java.util.HashMap<Integer, FlashChip>> CHIP_DB = new java.util.HashMap<>();

    static {
        // ── Winbond (0xEF) ──────────────────────────────────────
        addChip(0xEF, 0x13, 1 << 17, "W25Q80",   "8MBit / 1MB");
        addChip(0xEF, 0x14, 1 << 18, "W25Q16",   "16MBit / 2MB");
        addChip(0xEF, 0x15, 1 << 19, "W25Q32",   "32MBit / 4MB");
        addChip(0xEF, 0x16, 1 << 20, "W25Q64",   "64MBit / 8MB");
        addChip(0xEF, 0x17, 1 << 21, "W25Q128",  "128MBit / 16MB");
        addChip(0xEF, 0x18, 1 << 22, "W25Q256",  "256MBit / 32MB");
        addChip(0xEF, 0x19, 1 << 23, "W25Q512",  "512MBit / 64MB");

        // ── Macronix (0xC2) ─────────────────────────────────────
        addChip(0xC2, 0x13, 1 << 17, "MX25L800",  "8MBit / 1MB");
        addChip(0xC2, 0x14, 1 << 18, "MX25L1605", "16MBit / 2MB");
        addChip(0xC2, 0x15, 1 << 19, "MX25L3205", "32MBit / 4MB");
        addChip(0xC2, 0x16, 1 << 20, "MX25L6405", "64MBit / 8MB");
        addChip(0xC2, 0x17, 1 << 21, "MX25L12805","128MBit / 16MB");
        addChip(0xC2, 0x18, 1 << 22, "MX25L25635","256MBit / 32MB");
        addChip(0xC2, 0x19, 1 << 23, "MX25L51245","512MBit / 64MB");

        // ── GigaDevice (0xC8) ───────────────────────────────────
        addChip(0xC8, 0x13, 1 << 17, "GD25Q80",  "8MBit / 1MB");
        addChip(0xC8, 0x14, 1 << 18, "GD25Q16",  "16MBit / 2MB");
        addChip(0xC8, 0x15, 1 << 19, "GD25Q32",  "32MBit / 4MB");
        addChip(0xC8, 0x16, 1 << 20, "GD25Q64",  "64MBit / 8MB");
        addChip(0xC8, 0x17, 1 << 21, "GD25Q128", "128MBit / 16MB");
        addChip(0xC8, 0x18, 1 << 22, "GD25Q256", "256MBit / 32MB");

        // ── Puya Semiconductor (0x85) ───────────────────────────
        addChip(0x85, 0x14, 1 << 18, "P25Q16H",  "16MBit / 2MB");
        addChip(0x85, 0x15, 1 << 19, "P25Q32H",  "32MBit / 4MB");
        addChip(0x85, 0x16, 1 << 20, "P25Q64H",  "64MBit / 8MB");
        addChip(0x85, 0x17, 1 << 21, "P25Q128H", "128MBit / 16MB");
        addChip(0x85, 0x18, 1 << 22, "P25Q256H", "256MBit / 32MB");

        // ── XMC (0x20) ──────────────────────────────────────────
        addChip(0x20, 0x13, 1 << 17, "XM25QH80",  "8MBit / 1MB");
        addChip(0x20, 0x14, 1 << 18, "XM25QH16",  "16MBit / 2MB");
        addChip(0x20, 0x15, 1 << 19, "XM25QH32",  "32MBit / 4MB");
        addChip(0x20, 0x16, 1 << 20, "XM25QH64",  "64MBit / 8MB");
        addChip(0x20, 0x17, 1 << 21, "XM25QH128", "128MBit / 16MB");
        addChip(0x20, 0x18, 1 << 22, "XM25QH256", "256MBit / 32MB");

        // ── EON (0x1C) ──────────────────────────────────────────
        addChip(0x1C, 0x15, 1 << 19, "EN25Q32",   "32MBit / 4MB");
        addChip(0x1C, 0x16, 1 << 20, "EN25Q64",   "64MBit / 8MB");
        addChip(0x1C, 0x17, 1 << 21, "EN25Q128",  "128MBit / 16MB");

        // ── ISSI (0x9D) ─────────────────────────────────────────
        addChip(0x9D, 0x14, 1 << 18, "IS25LP016", "16MBit / 2MB");
        addChip(0x9D, 0x15, 1 << 19, "IS25LP032", "32MBit / 4MB");
        addChip(0x9D, 0x16, 1 << 20, "IS25LP064", "64MBit / 8MB");
        addChip(0x9D, 0x17, 1 << 21, "IS25LP128", "128MBit / 16MB");

        // ── AMIC (0x37) ─────────────────────────────────────────
        addChip(0x37, 0x15, 1 << 19, "A25L032",   "32MBit / 4MB");
        addChip(0x37, 0x16, 1 << 20, "A25L064",   "64MBit / 8MB");
        addChip(0x37, 0x17, 1 << 21, "A25L128",   "128MBit / 16MB");

        // ── Spansion/Cypress/Infineon (0x01) ────────────────────
        addChip(0x01, 0x15, 1 << 19, "S25FL032P",  "32MBit / 4MB");
        addChip(0x01, 0x16, 1 << 20, "S25FL064P",  "64MBit / 8MB");
        addChip(0x01, 0x17, 1 << 21, "S25FL128S",  "128MBit / 16MB");
        addChip(0x01, 0x18, 1 << 22, "S25FL256S",  "256MBit / 32MB");

        // ── Intel (0x89) ────────────────────────────────────────
        addChip(0x89, 0x89, 1 << 21, "QDIV/69F",  "128MBit / 16MB (Intel/Numonyx)");
    }

    private static void addChip(int mfrId, int devId, int sizeBytes, String name, String desc) {
        int sectorSize = sizeBytes >= (1 << 21) ? BLOCK_SIZE_64K : SECTOR_SIZE_4K;
        FlashChip chip = new FlashChip(mfrId, devId, sizeBytes, sectorSize, name, desc);
        int composite = (mfrId << 8) | devId;
        java.util.HashMap<Integer, FlashChip> mfrMap = CHIP_DB.get(mfrId);
        if (mfrMap == null) {
            mfrMap = new java.util.HashMap<>();
            CHIP_DB.put(mfrId, mfrMap);
        }
        mfrMap.put(devId, chip);
    }

    /**
     * Look up flash chip by manufacturer and device ID bytes.
     */
    public static FlashChip identify(int mfrId, int devId) {
        java.util.HashMap<Integer, FlashChip> mfrMap = CHIP_DB.get(mfrId);
        if (mfrMap != null) {
            FlashChip chip = mfrMap.get(devId);
            if (chip != null) return chip;
        }
        return null;
    }

    /**
     * Build a human-readable ID string from RDID response bytes.
     * Standard 3-byte response: [manufacturer, device_id_high, device_id_low]
     */
    public static String formatRdid(byte[] rdid) {
        if (rdid == null || rdid.length < 2) return "Unknown";
        int mfr = rdid[0] & 0xFF;
        int dev = (rdid.length >= 3) ? ((rdid[1] & 0xFF) << 8) | (rdid[2] & 0xFF) : (rdid[1] & 0xFF);
        return String.format("MFR=0x%02X (%s), DEV=0x%04X", mfr, getManufacturerName(mfr), dev);
    }

    public static String getManufacturerName(int mfrId) {
        switch (mfrId) {
            case 0x01: return "Spansion/Cypress";
            case 0x20: return "XMC";
            case 0x37: return "AMIC";
            case 0x1C: return "EON";
            case 0x85: return "Puya";
            case 0x89: return "Intel/Numonyx";
            case 0x9D: return "ISSI";
            case 0xC2: return "Macronix";
            case 0xC8: return "GigaDevice";
            case 0xEF: return "Winbond";
            default:   return "Unknown (0x" + Integer.toHexString(mfrId) + ")";
        }
    }

    /**
     * Get a common suffix for the chip size (for display).
     */
    public static String formatSize(int bytes) {
        if (bytes >= (1 << 20)) return (bytes >> 20) + " MB";
        if (bytes >= (1 << 10)) return (bytes >> 10) + " KB";
        return bytes + " B";
    }

    private SpiFlashProtocol() {}
}
