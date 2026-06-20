package cn.wch.ch341pardemo;

import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TermuxBridge extends Service {
 private static final String TAG = "TermuxBridge";
 // Local socket name for Termux X11/Proot bridge clients
 private static final String LOCAL_SOCKET_NAME = "ch341_bridge";
    private static final int VID = 0x1A86;
    private static final int[] PIDS = {0x7523, 0x5523, 0x7522, 0x5512, 0x7584, 0x7585, 0x7586};

    private ServerSocket server;
    private android.net.LocalServerSocket localServer;
    private volatile boolean running = true;
    private UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface intf;
    private UsbEndpoint epIn, epOut;
    private boolean usbReady = false;
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor();
    private UsbManager usbManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        startForegroundIfNeeded();
        new PermissionWatcher().start();
        Log.i(TAG, "Bridge service created, waiting for USB permission");
    }

    private void startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    "ch341", "CH341 Bridge", android.app.NotificationManager.IMPORTANCE_LOW);
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
            startForeground(1, new NotificationCompat.Builder(this, "ch341")
                    .setContentTitle("CH341A to Termux")
                    .setContentText("Waiting for USB device...")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true)
                    .build());
        } else {
            startForeground(1, new NotificationCompat.Builder(this)
                    .setContentTitle("CH341A to Termux")
                    .setContentText("Waiting for USB device...")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true)
                    .build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        closeAll();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void closeAll() {
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        try { if (localServer != null) localServer.close(); } catch (IOException ignored) {}
        try { if (conn != null && intf != null) conn.releaseInterface(intf); } catch (Exception ignored) {}
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        ioPool.shutdownNow();
    }

    private class PermissionWatcher extends Thread {
        @Override
        public void run() {
            while (running) {
                UsbDevice d = findDevice();
                if (d != null && usbManager.hasPermission(d)) {
                    mainHandler.post(() -> {
                        if (openUsb(d)) {
                            usbReady = true;
                            startLocalSocket();
                            updateNotification("Connected: " + getDeviceName(d));
                        }
                    });
                    break;
                }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        
        private UsbDevice findDevice() {
            HashMap<String, UsbDevice> map = usbManager.getDeviceList();
            for (UsbDevice d : map.values()) {
                if (d.getVendorId() == VID) {
                    for (int p : PIDS) if (d.getProductId() == p) return d;
                }
            }
            return null;
        }
        
        private String getDeviceName(UsbDevice d) {
            return String.format("0x%04X:0x%04X", d.getVendorId(), d.getProductId());
        }
    }

    private boolean openUsb(UsbDevice d) {
        conn = usbManager.openDevice(d);
        if (conn == null) {
            Log.e(TAG, "Failed to open USB device");
            return false;
        }
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface ui = d.getInterface(i);
            if (ui.getInterfaceClass() == 255 || ui.getInterfaceClass() == 2) {
                intf = ui;
                break;
            }
        }
        if (intf == null) {
            Log.e(TAG, "No suitable USB interface found");
            return false;
        }
        if (!conn.claimInterface(intf, true)) {
            Log.e(TAG, "Failed to claim USB interface");
            return false;
        }
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) epIn = ep;
            else epOut = ep;
        }
        if (epIn == null || epOut == null) {
            Log.e(TAG, "Could not find IN/OUT endpoints");
            return false;
        }
        // CH341 vendor init
        conn.controlTransfer(0x40, 0xA1, 0x0000, 0x0000, null, 0, 1000);
        conn.controlTransfer(0x40, 0x9A, 0x1312, 0x0000, null, 0, 1000);
        conn.controlTransfer(0x40, 0xA1, 0x0000, 0x0000, null, 0, 1000);
        device = d;
        return true;
    }

    private void startLocalSocket() {
        try {
            localServer = new android.net.LocalServerSocket(LOCAL_SOCKET_NAME);
            ioPool.submit(() -> {
                while (running) {
                    try {
                        android.net.LocalSocket client = localServer.accept();
                        ioPool.submit(new AsyncClientHandler(client));
                    } catch (IOException e) {
                        if (running) Log.e(TAG, "LocalSocket accept error", e);
                    }
                }
            });
            Log.i(TAG, "Local socket listening: " + LOCAL_SOCKET_NAME);
        } catch (IOException e) {
            Log.e(TAG, "LocalSocket creation failed", e);
        }
    }

    private void updateNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(1, new NotificationCompat.Builder(this, "ch341")
                    .setContentTitle("CH341A to Termux")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true)
                    .build());
        } else {
            startForeground(1, new NotificationCompat.Builder(this)
                    .setContentTitle("CH341A to Termux")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true)
                    .build());
        }
    }

    private class AsyncClientHandler implements Runnable {
        private final android.net.LocalSocket socket;
        private final ByteBuffer readBuffer = ByteBuffer.allocate(4096);
        private final ByteBuffer writeBuffer = ByteBuffer.allocate(4096);
        private UsbRequest usbReadRequest;
        
        AsyncClientHandler(android.net.LocalSocket s) { this.socket = s; }

        @Override
        public void run() {
            try {
                if (usbReady && device != null) {
                    String welcome = String.format("\nCH341 TERMUX BRIDGE:\nVID:PID 0x%04X:0x%04X\nENDPOINTS IN:%d OUT:%d\nREADY\n",
                            device.getVendorId(), device.getProductId(),
                            epIn != null ? epIn.getAddress() : -1,
                            epOut != null ? epOut.getAddress() : -1);
                    socket.getOutputStream().write(welcome.getBytes());
                    socket.getOutputStream().flush();
                }

                // Setup async USB read
                usbReadRequest = new UsbRequest();
                usbReadRequest.initialize(conn, epIn);
                
                byte[] buf = new byte[4096];
                while (running && !socket.isClosed() && usbReady) {
                    int r = socket.getInputStream().read(buf);
                    if (r > 0 && conn != null && epOut != null) {
                        // Async write to USB
                        conn.bulkTransfer(epOut, buf, r, 5000);
                        // Queue async read
                        readBuffer.clear();
                        readBuffer.limit(writeBuffer.capacity());
                        usbReadRequest.queue(readBuffer, writeBuffer.capacity());
                    }
                    
                    // Check for completed USB read
                    if (usbReadRequest != null && conn.requestWait() == usbReadRequest) {
                        int m = readBuffer.position();
                        if (m > 0) {
                            readBuffer.flip();
                            byte[] response = new byte[m];
                            readBuffer.get(response);
                            socket.getOutputStream().write(response);
                            socket.getOutputStream().flush();
                        }
                        // Re-queue for next read
                        readBuffer.clear();
                        usbReadRequest.queue(readBuffer, writeBuffer.capacity());
                    }
                    
                    Thread.sleep(1); // Yield
                }
            } catch (Exception e) {
                Log.e(TAG, "async client error", e);
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
