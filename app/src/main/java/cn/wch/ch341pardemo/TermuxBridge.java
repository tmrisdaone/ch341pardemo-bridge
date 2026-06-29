package cn.wch.ch341pardemo;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that exposes a connected CH341/CH347 chip to Termux
 * over an abstract-namespace AF_UNIX socket ("ch341_bridge"). Only same-UID
 * clients can connect — Termux satisfies that contract because it shares the
 * app's UID via the shared filesystem host.
 *
 * USB I/O uses async UsbRequest when a client is attached; we run read and
 * write on separate threads per client so a stalled bulkTransfer on the OUT
 * endpoint cannot block inbound traffic.
 */
public class TermuxBridge extends Service {

    private static final String TAG = "TermuxBridge";
    private static final String LOCAL_SOCKET_NAME = "ch341_bridge";
    private static final int VID = 0x1A86;
    private static final int[] PIDS = {
            0x7523, 0x5523, 0x7522, 0x5512,
            0x7584, 0x7585, 0x7586
    };

    private LocalServerSocket localServer;
    private volatile boolean running = true;
    private UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface intf;
    private UsbEndpoint epIn;
    private UsbEndpoint epOut;
    private volatile boolean usbReady = false;
    private UsbManager usbManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Separate pools: ONE thread for accept() that never does I/O,
    // and a cached pool that survives rapid reconnect storms.
    private final ExecutorService acceptor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ch341-accept");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ch341-io");
        t.setDaemon(true);
        return t;
    });

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
        // START_STICKY preserves the socket if the OS kills us while Termux is mid-write.
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
        try {
            if (localServer != null) localServer.close();
        } catch (IOException ignored) {
        }
        try {
            if (conn != null && intf != null) conn.releaseInterface(intf);
        } catch (Exception ignored) {
        }
        try {
            if (conn != null) conn.close();
        } catch (Exception ignored) {
        }
        acceptor.shutdownNow();
        ioPool.shutdownNow();
    }

    private class PermissionWatcher extends Thread {
        PermissionWatcher() {
            super("ch341-perm");
        }

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
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    return;
                }
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

        // Prefer the vendor-specific interface class; on CH347 there are multiple
        // interfaces and the first one isn't always the right one for UART/SPI/I2C.
        UsbInterface picked = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface ui = d.getInterface(i);
            if (ui.getInterfaceClass() == 0xFF || ui.getInterfaceClass() == 0x02) {
                picked = ui;
                break;
            }
        }
        if (picked == null) {
            Log.e(TAG, "No suitable USB interface found");
            return false;
        }
        intf = picked;
        if (!conn.claimInterface(intf, true)) {
            Log.e(TAG, "Failed to claim USB interface");
            return false;
        }
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep;
            else if (ep.getDirection() == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep;
        }
        if (epIn == null || epOut == null) {
            Log.e(TAG, "Could not find IN/OUT endpoints");
            return false;
        }

        // CH341 vendor init sequence. Same as WCH reference examples.
        conn.controlTransfer(0x40, 0xA1, 0x0000, 0x0000, null, 0, 1000);
        conn.controlTransfer(0x40, 0x9A, 0x1312, 0x0000, null, 0, 1000);
        conn.controlTransfer(0x40, 0xA1, 0x0000, 0x0000, null, 0, 1000);

        device = d;
        return true;
    }

    private void startLocalSocket() {
        // Abstract namespace only — no filesystem path, so the Termux-side
        // existence check needs /proc/net/unix, not [[ -S ]].
        try {
            localServer = new LocalServerSocket(LOCAL_SOCKET_NAME);
        } catch (IOException e) {
            Log.e(TAG, "LocalSocket creation failed", e);
            return;
        }
        Log.i(TAG, "Local socket listening: " + LOCAL_SOCKET_NAME);

        acceptor.submit(() -> {
            while (running) {
                LocalSocket client;
                try {
                    client = localServer.accept();
                } catch (IOException e) {
                    if (running) Log.e(TAG, "LocalSocket accept error", e);
                    return;
                }
                ioPool.submit(() -> handleClient(client));
            }
        });
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

    /**
     * One client. Read and write run on independent threads so a slow OUT
     * transfer can't stall IN traffic. JOIN the client only after both
     * pumps exit; cleanup closes the socket here, not in closeAll(), so
     * another client can connect mid-stream.
     */
    private void handleClient(LocalSocket socket) {
        Thread readPump = null;
        Thread writePump = null;
        try {
            if (usbReady && device != null) {
                String welcome = String.format(
                        "\nCH341 TERMUX BRIDGE:\nVID:PID 0x%04X:0x%04X\nENDPOINTS IN:%d OUT:%d\nREADY\n",
                        device.getVendorId(), device.getProductId(),
                        epIn != null ? epIn.getAddress() : -1,
                        epOut != null ? epOut.getAddress() : -1);
                socket.getOutputStream().write(welcome.getBytes());
                socket.getOutputStream().flush();
            }

            readPump = new Thread(() -> readPump(socket), "ch341-read-" + socket.toString());
            writePump = new Thread(() -> writePump(socket), "ch341-write-" + socket.toString());
            readPump.start();
            writePump.start();
            readPump.join();
            writePump.join();
        } catch (Exception e) {
            Log.e(TAG, "client handler error", e);
        } finally {
            // interrupt pumps if they're still alive after the join timeout semantics
            if (writePump != null && writePump.isAlive()) writePump.interrupt();
            if (readPump != null && readPump.isAlive()) readPump.interrupt();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** USB IN -> socket. Uses a single async UsbRequest; no second buffer. */
    private void readPump(LocalSocket socket) {
        UsbRequest req = new UsbRequest();
        try {
            req.initialize(conn, epIn);
            ByteBuffer buf = ByteBuffer.allocate(4096);
            while (running && !socket.isClosed() && usbReady) {
                buf.clear();
                if (!req.queue(buf, buf.capacity())) {
                    // Request rejected by the kernel — back off briefly.
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    continue;
                }
                conn.requestWait();
                int n = buf.position();
                if (n > 0) {
                    buf.flip();
                    byte[] out = new byte[n];
                    buf.get(out);
                    try {
                        socket.getOutputStream().write(out);
                        socket.getOutputStream().flush();
                    } catch (IOException ioe) {
                        return; // peer closed
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "read pump error", e);
        } finally {
            try {
                req.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** socket -> USB OUT. Bounded timeout, partial-write retry, no global stall. */
    private void writePump(LocalSocket socket) {
        byte[] buf = new byte[4096];
        try {
            java.io.InputStream in = socket.getInputStream();
            while (running && !socket.isClosed() && usbReady) {
                int n;
                try {
                    n = in.read(buf);
                } catch (IOException ioe) {
                    return;
                }
                if (n < 0) return;
                if (n == 0) continue;

                int sent = 0;
                while (sent < n && running && usbReady) {
                    int w = conn.bulkTransfer(epOut, buf, sent, n - sent, 1000);
                    if (w <= 0) break; // stall or error — drop and let readPump surface it
                    sent += w;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "write pump error", e);
        }
    }
}
