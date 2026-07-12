package cn.wch.ch341pardemo.domain;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import cn.wch.ch341lib.CH341Manager;
import cn.wch.ch341pardemo.data.HexUtil;
import cn.wch.ch341pardemo.domain.SpiFlashProtocol.FlashChip;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Background service for SPI flash programming operations.
 * <p>
 * Runs flash operations (detect, read, erase, write, verify) off the main
 * thread and reports progress via broadcasts for the UI to display.
 */
public class FlasherService extends Service {

    private static final String TAG = "FlasherService";

    // Actions
    public static final String ACTION_DETECT = "cn.wch.flasher.DETECT";
    public static final String ACTION_READ = "cn.wch.flasher.READ";
    public static final String ACTION_ERASE = "cn.wch.flasher.ERASE";
    public static final String ACTION_WRITE = "cn.wch.flasher.WRITE";
    public static final String ACTION_VERIFY = "cn.wch.flasher.VERIFY";

    // Status broadcasts
    public static final String ACTION_PROGRESS = "cn.wch.flasher.PROGRESS";
    public static final String ACTION_RESULT = "cn.wch.flasher.RESULT";
    public static final String ACTION_ERROR = "cn.wch.flasher.ERROR";
    public static final String EXTRA_STAGE = "stage";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_CHIP_NAME = "chip_name";
    public static final String EXTRA_CHIP_SIZE = "chip_size";
    public static final String EXTRA_CHIP_INFO = "chip_info";
    public static final String EXTRA_FILE_PATH = "file_path";

    private static final String NOTIF_CHANNEL_ID = "flash_ops";

    private SpiFlashEngine engine;
    private CH341Manager ch341Manager;
    private UsbManager usbManager;
    private UsbDevice flashDevice;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        ch341Manager = CH341Manager.getInstance();
        engine = new SpiFlashEngine();
        Log.i(TAG, "Flasher service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        showNotification("Starting...");

        String action = intent.getAction();
        if (action == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Find the CH341 device
        String deviceKey = intent.getStringExtra("device_key");
        flashDevice = findDevice(deviceKey);

        if (flashDevice == null) {
            broadcastError("No CH341 device found. Plug it in first.");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Initialize the SPI engine
        if (!engine.init(flashDevice, usbManager, ch341Manager)) {
            broadcastError("Failed to initialize SPI engine");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Execute the requested operation in a background thread
        new Thread(() -> {
            try {
                executeOperation(action, intent);
            } catch (Exception e) {
                Log.e(TAG, "Operation failed", e);
                broadcastError(e.getMessage());
            } finally {
                engine.close();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "FlasherWorker").start();

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        engine.close();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Operation Dispatch ─────────────────────────────────────────

    private void executeOperation(String action, Intent intent) throws Exception {
        switch (action) {
            case ACTION_DETECT:
                doDetect();
                break;
            case ACTION_READ:
                String readPath = intent.getStringExtra(EXTRA_FILE_PATH);
                doRead(readPath);
                break;
            case ACTION_ERASE:
                doErase();
                break;
            case ACTION_WRITE:
                String writePath = intent.getStringExtra(EXTRA_FILE_PATH);
                doWrite(writePath);
                break;
            case ACTION_VERIFY:
                String verifyPath = intent.getStringExtra(EXTRA_FILE_PATH);
                doVerify(verifyPath);
                break;
        }
    }

    // ── Operations ─────────────────────────────────────────────────

    private void doDetect() throws InterruptedException {
        showNotification("Detecting flash chip...");
        broadcastProgress("Detecting", 0, 1);

        FlashChip chip = engine.detectChip();
        if (chip != null) {
            String info = chip.name + " (" + chip.description + ")";
            broadcastChipDetected(chip);
            broadcastResult("Detected: " + info);
            showNotification("Found: " + chip.name);
        } else {
            // Try to get raw RDID bytes anyway
            broadcastError("Unknown flash chip. Check connections.");
        }
    }

    private void doRead(String filePath) throws Exception {
        if (filePath == null) {
            broadcastError("No output file specified");
            return;
        }

        FlashChip chip = engine.getDetectedChip();
        if (chip == null) {
            broadcastError("No chip detected. Run detect first.");
            return;
        }

        int chipSize = chip.sizeBytes;
        showNotification("Reading " + chip.name + " (" + chipSize + " bytes)");

        byte[] data = new byte[chipSize];
        boolean ok = engine.read(0, data, 0, chipSize, new SpiFlashEngine.ProgressCallback() {
            @Override public void onProgress(String stage, int current, int total) {
                broadcastProgress(stage, current, total);
            }
            @Override public void onMessage(String msg) { broadcastProgress("Reading", -1, -1); }
            @Override public void onError(String err) { broadcastError(err); }
        });

        if (ok) {
            // Save to file
            File file = new File(filePath);
            file.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            }
            broadcastResult("Read complete: " + file.getName()
                    + " (" + chipSize + " bytes)");
            showNotification("Read complete");
        }
    }

    private void doErase() throws Exception {
        FlashChip chip = engine.getDetectedChip();
        if (chip == null) {
            broadcastError("No chip detected");
            return;
        }

        showNotification("Erasing " + chip.name + "...");
        boolean ok = engine.eraseRange(0, chip.sizeBytes, new SpiFlashEngine.ProgressCallback() {
            @Override public void onProgress(String stage, int current, int total) {
                broadcastProgress(stage, current, total);
            }
            @Override public void onMessage(String msg) {}
            @Override public void onError(String err) { broadcastError(err); }
        });

        if (ok) {
            broadcastResult("Chip erase complete");
            showNotification("Erase complete");
        }
    }

    private void doWrite(String filePath) throws Exception {
        if (filePath == null) {
            broadcastError("No input file specified");
            return;
        }

        FlashChip chip = engine.getDetectedChip();
        if (chip == null) {
            broadcastError("No chip detected");
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            broadcastError("File not found: " + filePath);
            return;
        }

        byte[] data = new byte[(int) Math.min(file.length(), chip.sizeBytes)];
        try (FileInputStream fis = new FileInputStream(file)) {
            int read = fis.read(data);
            if (read < 0) {
                broadcastError("Failed to read input file");
                return;
            }
        }

        showNotification("Writing " + file.getName() + " (" + data.length + " bytes)...");

        // Erase the range first
        broadcastProgress("Erasing", 0, 1);
        engine.eraseRange(0, data.length, new SpiFlashEngine.ProgressCallback() {
            @Override public void onProgress(String stage, int current, int total) {
                broadcastProgress(stage, current, total);
            }
            @Override public void onMessage(String msg) {}
            @Override public void onError(String err) { broadcastError(err); }
        });

        // Program the flash
        boolean ok = engine.program(0, data, 0, data.length,
            new SpiFlashEngine.ProgressCallback() {
                @Override public void onProgress(String stage, int current, int total) {
                    broadcastProgress(stage, current, total);
                }
                @Override public void onMessage(String msg) {}
                @Override public void onError(String err) { broadcastError(err); }
            });

        if (ok) {
            broadcastResult("Write complete: " + file.getName());
            showNotification("Write complete");
        }
    }

    private void doVerify(String filePath) throws Exception {
        if (filePath == null) {
            broadcastError("No file specified");
            return;
        }

        FlashChip chip = engine.getDetectedChip();
        if (chip == null) {
            broadcastError("No chip detected");
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            broadcastError("File not found");
            return;
        }

        byte[] data = new byte[(int) Math.min(file.length(), chip.sizeBytes)];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(data);
        }

        showNotification("Verifying...");
        boolean ok = engine.verify(0, data, 0, data.length,
            new SpiFlashEngine.ProgressCallback() {
                @Override public void onProgress(String stage, int current, int total) {
                    broadcastProgress(stage, current, total);
                }
                @Override public void onMessage(String msg) {}
                @Override public void onError(String err) { broadcastError(err); }
            });

        if (ok) {
            broadcastResult("Verify OK: " + data.length + " bytes match");
            showNotification("Verify: OK");
        }
    }

    // ── Broadcasting ───────────────────────────────────────────────

    private void broadcastProgress(String stage, int current, int total) {
        Intent intent = new Intent(ACTION_PROGRESS);
        intent.putExtra(EXTRA_STAGE, stage);
        intent.putExtra(EXTRA_PROGRESS, current);
        intent.putExtra(EXTRA_TOTAL, total);
        androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(this).sendBroadcast(intent);
        Log.d(TAG, stage + ": " + current + "/" + total);
    }

    private void broadcastResult(String message) {
        Intent intent = new Intent(ACTION_RESULT);
        intent.putExtra(EXTRA_MESSAGE, message);
        androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(this).sendBroadcast(intent);
        Log.i(TAG, "Result: " + message);
    }

    private void broadcastError(String message) {
        Intent intent = new Intent(ACTION_ERROR);
        intent.putExtra(EXTRA_MESSAGE, message);
        androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(this).sendBroadcast(intent);
        Log.e(TAG, "Error: " + message);
    }

    private void broadcastChipDetected(FlashChip chip) {
        Intent intent = new Intent(ACTION_RESULT);
        intent.putExtra(EXTRA_CHIP_NAME, chip.name);
        intent.putExtra(EXTRA_CHIP_SIZE, chip.sizeBytes);
        intent.putExtra(EXTRA_CHIP_INFO, chip.name + " (" + chip.description + ")");
        androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(this).sendBroadcast(intent);
    }

    // ── Helpers ────────────────────────────────────────────────────

    private UsbDevice findDevice(String deviceKey) {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (isCh341Device(d)) {
                if (deviceKey == null || d.getDeviceName().equals(deviceKey)) {
                    return d;
                }
            }
        }
        return null;
    }

    private boolean isCh341Device(UsbDevice d) {
        if (d.getVendorId() != VID_QINHENG) return false;
        for (int pid : CH341_PIDS) {
            if (d.getProductId() == pid) return true;
        }
        return true; // Accept any QinHeng device
    }

    private void showNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    NOTIF_CHANNEL_ID, "Flash Operations",
                    android.app.NotificationManager.IMPORTANCE_LOW);
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }

        startForeground(2, new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("CH341 SPI Flasher")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build());
    }
}
