package cn.wch.ch341pardemo;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that bridges CH341A USB devices to Termux via a local Unix socket.
 *
 * <p>The service detects CH341A devices, opens a raw USB bulk transfer connection,
 * and exposes it over an AF_UNIX LocalServerSocket so Termux/Proot processes
 * can communicate with the chip directly.
 *
 * <p>Supports both UART mode (default) and SPI flash mode. When a flash chip
 * is detected via the Flasher tab, the bridge operates in passthrough mode.
 *
 * <p>No Shizuku / root required. Uses standard Android USB Host API.
 */
public class TermuxBridge extends Service {

    private static final String TAG = "TermuxBridge";
    private static final String LOCAL_SOCKET_NAME = "ch341_bridge";
    private static final String ACTION_USB_PERMISSION = "cn.wch.ch341pardemo.USB_PERMISSION";

    private static final int VID = 0x1A86;
    private static final int[] PIDS = {0x7523, 0x5523, 0x7522, 0x5512, 0x7584, 0x7585, 0x7586};

    public static final String ACTION_START = "cn.wch.TermuxBridge.START";
    public static final String ACTION_STOP = "cn.wch.TermuxBridge.STOP";

    private android.net.LocalServerSocket localServer;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface intf;
    private UsbEndpoint epIn, epOut;
    private UsbManager usbManager;

    private final ExecutorService ioPool = Executors.newCachedThreadPool();

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        Log.i(TAG, "Bridge service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopBridge();
            return START_NOT_STICKY;
        }

        // ACTION_START or default
        startForegroundIfNeeded();
        startBridge();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopBridge();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Bridge Lifecycle ───────────────────────────────────────────

    private synchronized void startBridge() {
        if (running.get()) {
            Log.d(TAG, "Bridge already running");
            return;
        }

        // Register USB permission receiver
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbPermissionReceiver, filter);

        // Register hotplug receiver
        IntentFilter hotplug = new IntentFilter();
        hotplug.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        hotplug.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbHotplugReceiver, hotplug);

        // Look for existing CH341 device
        UsbDevice d = findDevice();
        if (d != null) {
            Log.i(TAG, "Found CH341 device: " + getDeviceName(d));
            if (usbManager.hasPermission(d)) {
                openUsb(d);
            } else {
                requestPermission(d);
            }
        } else {
            Log.i(TAG, "No CH341 device found, waiting for plug-in");
            updateNotification("Waiting for USB device...");
            running.set(true); // Keep service alive waiting for hotplug
        }
    }

    private synchronized void stopBridge() {
        Log.i(TAG, "Stopping bridge...");
        running.set(false);
        unregisterReceivers();
        closeAll();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void unregisterReceivers() {
        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(usbHotplugReceiver); } catch (Exception ignored) {}
    }

    // ── USB Permission ─────────────────────────────────────────────

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            Log.i(TAG, "Permission " + (granted ? "granted" : "denied") + " for "
                    + (d != null ? getDeviceName(d) : "?"));
            if (granted && d != null) {
                openUsb(d);
            }
        }
    };

    // ── Hotplug ────────────────────────────────────────────────────

    private final BroadcastReceiver usbHotplugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null && isCh341Device(d) && !running.get()) {
                    Log.i(TAG, "CH341 attached: " + getDeviceName(d));
                    if (usbManager.hasPermission(d)) {
                        openUsb(d);
                    } else {
                        requestPermission(d);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null && device != null && d.getDeviceId() == device.getDeviceId()) {
                    Log.w(TAG, "CH341 detached: " + getDeviceName(d));
                    closeAll();
                    updateNotification("Device removed");
                    // Keep service alive waiting for re-plug
                    running.set(true);
                }
            }
        }
    };

    // ── USB Open ───────────────────────────────────────────────────

    private void requestPermission(UsbDevice d) {
        Log.i(TAG, "Requesting permission for " + getDeviceName(d));
        PendingIntent pi = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION),
                Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        usbManager.requestPermission(d, pi);
    }

    private boolean openUsb(UsbDevice d) {
        closeAll(); // Close any previous connection

        conn = usbManager.openDevice(d);
        if (conn == null) {
            Log.e(TAG, "Failed to open USB device");
            return false;
        }

        // Find vendor-specific interface
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface ui = d.getInterface(i);
            if (ui.getInterfaceClass() == 255) {
                intf = ui;
                break;
            }
        }
        if (intf == null) {
            Log.e(TAG, "No vendor-specific interface found");
            conn.close();
            return false;
        }

        if (!conn.claimInterface(intf, true)) {
            Log.e(TAG, "Failed to claim interface");
            conn.close();
            return false;
        }

        // Find bulk endpoints
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) epIn = ep;
                else epOut = ep;
            }
        }
        if (epIn == null || epOut == null) {
            Log.e(TAG, "Missing bulk endpoints");
            conn.releaseInterface(intf);
            conn.close();
            return false;
        }

        // CH341 init sequence
        conn.controlTransfer(0x40, 0xA1, 0x0000, 0x0000, null, 0, 1000);
        conn.controlTransfer(0x40, 0x9A, 0x1312, 0x0000, null, 0, 1000);
        conn.controlTransfer(0x40, 0xA1, 0x0000, 0x0000, null, 0, 1000);

        device = d;
        running.set(true);

        // Start local socket server
        startLocalSocket();
        updateNotification("Connected: " + getDeviceName(d));
        Log.i(TAG, "Bridge ready: " + getDeviceName(d));
        return true;
    }

    // ── Local Socket ───────────────────────────────────────────────

    private void startLocalSocket() {
        try {
            localServer = new android.net.LocalServerSocket(LOCAL_SOCKET_NAME);
            Log.i(TAG, "Local socket: @" + LOCAL_SOCKET_NAME);
            ioPool.submit(this::acceptLoop);
        } catch (IOException e) {
            Log.e(TAG, "Socket creation failed", e);
        }
    }

    private void acceptLoop() {
        while (running.get() && localServer != null) {
            try {
                android.net.LocalSocket client = localServer.accept();
                ioPool.submit(new ClientHandler(client));
            } catch (IOException e) {
                if (running.get()) Log.e(TAG, "Accept error", e);
            }
        }
    }

    // ── Client Handler ─────────────────────────────────────────────

    private class ClientHandler implements Runnable {
        private final android.net.LocalSocket socket;

        ClientHandler(android.net.LocalSocket s) { this.socket = s; }

        @Override
        public void run() {
            Log.d(TAG, "Client connected");
            try {
                // Welcome banner
                if (device != null) {
                    String welcome = String.format(
                            "CH341 TERMUX BRIDGE\nVID:PID %s\nENDPOINTS OUT:0x%02X IN:0x%02X\nREADY\n",
                            getDeviceName(device),
                            epOut != null ? epOut.getAddress() : -1,
                            epIn != null ? epIn.getAddress() : -1);
                    socket.getOutputStream().write(welcome.getBytes());
                    socket.getOutputStream().flush();
                }

                // Client → USB (daemon thread)
                Thread writer = new Thread(() -> {
                    byte[] buf = new byte[4096];
                    try {
                        int n;
                        while (running.get() && !socket.isClosed()
                                && (n = socket.getInputStream().read(buf)) != -1) {
                            if (n > 0 && conn != null && epOut != null) {
                                conn.bulkTransfer(epOut, buf, n, 5000);
                            }
                        }
                    } catch (IOException e) {
                        if (running.get()) Log.d(TAG, "Client write ended");
                    }
                }, "client-writer");
                writer.setDaemon(true);
                writer.start();

                // USB → Client (main loop)
                byte[] readBuf = new byte[4096];
                while (running.get() && !socket.isClosed() && conn != null && epIn != null) {
                    int n = conn.bulkTransfer(epIn, readBuf, readBuf.length, 1000);
                    if (n > 0) {
                        socket.getOutputStream().write(readBuf, 0, n);
                        socket.getOutputStream().flush();
                    }
                }

                socket.close();
                if (writer.isAlive()) writer.interrupt();
                Log.d(TAG, "Client disconnected");

            } catch (Exception e) {
                Log.e(TAG, "Client error", e);
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private UsbDevice findDevice() {
        HashMap<String, UsbDevice> map = usbManager.getDeviceList();
        for (UsbDevice d : map.values()) {
            if (isCh341Device(d)) return d;
        }
        return null;
    }

    private boolean isCh341Device(UsbDevice d) {
        if (d.getVendorId() != VID) return false;
        for (int pid : PIDS) {
            if (d.getProductId() == pid) return true;
        }
        return true; // Accept any QinHeng device if PID not in list
    }

    private static String getDeviceName(UsbDevice d) {
        return String.format("0x%04X:0x%04X", d.getVendorId(), d.getProductId());
    }

    private void closeAll() {
        try { if (localServer != null) localServer.close(); localServer = null; } catch (Exception ignored) {}
        try { if (conn != null && intf != null) conn.releaseInterface(intf); } catch (Exception ignored) {}
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        intf = null; epIn = null; epOut = null; conn = null; device = null;
    }

    private void startForegroundIfNeeded() {
        String channelId = "ch341";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    channelId, "CH341 Bridge",
                    android.app.NotificationManager.IMPORTANCE_LOW);
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
        updateNotification("Waiting for USB device...");
    }

    private void updateNotification(String text) {
        String channelId = "ch341";
        startForeground(1, new NotificationCompat.Builder(this, channelId)
                .setContentTitle("CH341A to Termux")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build());
    }
}
