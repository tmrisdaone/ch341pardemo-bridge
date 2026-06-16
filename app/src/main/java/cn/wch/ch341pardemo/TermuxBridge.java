package cn.wch.ch341pardemo;

import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbConstants;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;

public class TermuxBridge extends Service {
    private static final String TAG = "TermuxBridge";
    private static final int PORT = 4444;
    private static final int VID = 0x1A86;
    private static final int[] PIDS = {0x7523, 0x5523, 0x7522, 0x5512, 0x7584, 0x7585, 0x7586};

    private ServerSocket server;
    private volatile boolean running = true;
    private UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface intf;
    private UsbEndpoint epIn, epOut;

    @Override
    public void onCreate() {
        super.onCreate();
        boolean started = openUsb();
        if (!started) {
            Log.e(TAG, "openUsb failed");
        }
        startForegroundIfNeeded();
        new BridgeThread().start();
        Log.i(TAG, "Bridge started on 127.0.0.1:" + PORT);
    }

    private void startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    "ch341", "CH341 Bridge", android.app.NotificationManager.IMPORTANCE_LOW);
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
            startForeground(1, new NotificationCompat.Builder(this, "ch341")
                    .setContentTitle("CH41A→Termux")
                    .setContentText("127.0.0.1:4444")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true)
                    .build());
        } else {
            startForeground(1, new NotificationCompat.Builder(this)
                    .setContentTitle("CH41A→Termux")
                    .setContentText("127.0.0.1:4444")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true)
                    .build());
        }
    }

    private boolean openUsb() {
        UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
        HashMap<String, UsbDevice> map = mgr.getDeviceList();
        for (UsbDevice d : map.values()) {
            if (d.getVendorId() == VID) {
                boolean pidMatch = false;
                for (int p : PIDS) if (d.getProductId() == p) { pidMatch = true; break; }
                if (pidMatch) { device = d; break; }
            }
        }
        if (device == null) {
            Log.e(TAG, "No CH341 device");
            return false;
        }
        conn = mgr.openDevice(device);
        if (conn == null) return false;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface ui = device.getInterface(i);
            if (ui.getInterfaceClass() == 255 || ui.getInterfaceClass() == 2) { // VENDOR_SPEC or CDC
                intf = ui;
                break;
            }
        }
        if (intf == null) return false;
        if (!conn.claimInterface(intf, true)) return false;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) epIn = ep;
            else epOut = ep;
        }
        if (epIn == null || epOut == null) return false;

        // CH341 vendor init
        conn.controlTransfer(0x40, 0xA1, 0x0000, 0x0000, null, 0, 1000);
        conn.controlTransfer(0x40, 0x9A, 0x1312, 0x0000, null, 0, 1000);
        conn.controlTransfer(0x40, 0xA1, 0x0000, 0x0000, null, 0, 1000);
        return true;
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
        try { if (conn != null) conn.releaseInterface(intf); } catch (Exception ignored) {}
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
    }

    private class BridgeThread extends Thread {
        @Override
        public void run() {
            try {
                server = new ServerSocket();
                server.bind(new java.net.InetSocketAddress("127.0.0.1", PORT));
                server.setReuseAddress(true);
                while (running) {
                    Socket s = server.accept();
                    new ClientHandler(s).start();
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error", e);
            }
        }
    }

    private class ClientHandler extends Thread {
        private final Socket s;
        ClientHandler(Socket s) { this.s = s; }

        @Override
        public void run() {
            try {
                s.setTcpNoDelay(true);
                s.getOutputStream().write(("\nCH341 TERMUX BRIDGE:\n"
                        + "VID:PID 0x" + device.getVendorId() + ":0x" + device.getProductId() + "\n"
                        + "ENDPOINTS IN:" + epIn.getAddress() + " OUT:" + epOut.getAddress() + "\n"
                        + "READY\n").getBytes());
                byte[] buf = new byte[4096];
                byte[] in = new byte[4096];
                while (running && !s.isClosed()) {
                    int r = s.getInputStream().read(buf);
                    if (r > 0) {
                        int n = conn.bulkTransfer(epOut, buf, r, 5000);
                        if (n > 0) {
                            int m = conn.bulkTransfer(epIn, in, in.length, 100);
                            if (m > 0) s.getOutputStream().write(in, 0, m);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "client error", e);
            } finally {
                try { s.close(); } catch (IOException ignored) {}
            }
        }
    }
}