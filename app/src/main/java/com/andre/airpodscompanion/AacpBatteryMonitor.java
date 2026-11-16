package com.andre.airpodscompanion;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

/** Reads the battery notifications AirPods expose on their classic AACP L2CAP channel. */
final class AacpBatteryMonitor {
    interface Listener {
        void onBattery(AirPodsBatteryStore.Snapshot snapshot);
        void onError(String message);
    }

    private static final int AACP_PSM = 0x1001;
    private static final byte[] CONNECT = hex("00000400010002000000000000000000");
    private static final byte[] INIT = hex("040004004D00D700000000000000");
    private static final byte[] BATTERY_NOTIFICATIONS = hex("040004000F00FFFFFFFF");
    private static final byte[] BATTERY_PREFIX = hex("040004000400");

    private final BluetoothDevice device;
    private final Context context;
    private final String name;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile BluetoothSocket socket;
    private Thread worker;

    AacpBatteryMonitor(Context context, BluetoothDevice device, String name, Listener listener) {
        this.context = context.getApplicationContext();
        this.device = device;
        this.name = name;
        this.listener = listener;
    }

    synchronized void start() {
        if (worker != null) return;
        worker = new Thread(this::run, "airpods-aacp-battery");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        stopped = true;
        BluetoothSocket active = socket;
        if (active != null) {
            try { active.close(); } catch (IOException ignored) { }
        }
        Thread activeWorker = worker;
        if (activeWorker != null) activeWorker.interrupt();
    }

    @SuppressLint("MissingPermission")
    private void run() {
        while (!stopped) {
            try {
                runSession();
            } catch (Exception error) {
                if (!stopped) listener.onError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            } finally {
                BluetoothSocket active = socket;
                socket = null;
                if (active != null) try { active.close(); } catch (IOException ignored) { }
            }
            if (!stopped) {
                try { Thread.sleep(5000L); }
                catch (InterruptedException ignored) { if (stopped) return; }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void runSession() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/");
        }
        Method create = device.getClass().getMethod("createL2capSocket", int.class);
        socket = (BluetoothSocket) create.invoke(device, AACP_PSM);
        socket.connect();
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        output.write(CONNECT);
        output.flush();

        // The phone responds to the handshake before accepting subscription requests.
        byte[] handshake = new byte[256];
        int count = input.read(handshake);
        if (count <= 0 || stopped) return;
        output.write(INIT);
        output.flush();
        Thread.sleep(100L);
        output.write(BATTERY_NOTIFICATIONS);
        output.flush();
        Thread.sleep(200L);
        output.write(CONNECT);
        output.flush();
        Thread.sleep(200L);
        output.write(INIT);
        output.flush();
        Thread.sleep(200L);
        output.write(BATTERY_NOTIFICATIONS);
        output.flush();

        byte[] buffer = new byte[256];
        byte[] pending = new byte[512];
        int pendingLength = 0;
        while (!stopped) {
            int read = input.read(buffer);
            if (read < 0) break;
            int copy = Math.min(read, pending.length - pendingLength);
            if (copy <= 0) {
                pendingLength = 0;
                continue;
            }
            System.arraycopy(buffer, 0, pending, pendingLength, copy);
            pendingLength += copy;
            int cursor = 0;
            while (cursor + 22 <= pendingLength) {
                int start = findPrefix(pending, cursor, pendingLength);
                if (start < 0) {
                    cursor = Math.max(cursor, pendingLength - (BATTERY_PREFIX.length - 1));
                    break;
                }
                if (start + 22 > pendingLength) {
                    cursor = start;
                    break;
                }
                AirPodsBatteryStore.Snapshot report = parse(pending, start);
                if (report != null) listener.onBattery(report);
                cursor = start + 22;
            }
            if (cursor > 0) {
                pendingLength -= cursor;
                System.arraycopy(pending, cursor, pending, 0, pendingLength);
            } else if (pendingLength == pending.length) {
                System.arraycopy(pending, pendingLength - 5, pending, 0, 5);
                pendingLength = 5;
            }
        }
    }

    private AirPodsBatteryStore.Snapshot parse(byte[] packet, int offset) {
        AirPodsBatteryStore.Snapshot previous = AirPodsBatteryStore.read(context);
        int left = previous.left;
        int right = previous.right;
        int caseLevel = previous.caseBattery;
        int leftCharging = previous.leftCharging;
        int rightCharging = previous.rightCharging;
        int caseCharging = previous.caseCharging;
        boolean found = false;

        // AACP battery event: event byte followed by left, right, and case records.
        if ((packet[offset + 7] & 0xff) == 0x04) {
            left = batteryLevel(packet[offset + 9], packet[offset + 10]);
            leftCharging = charging(packet[offset + 10]);
            found = true;
        }
        if ((packet[offset + 12] & 0xff) == 0x02) {
            right = batteryLevel(packet[offset + 14], packet[offset + 15]);
            rightCharging = charging(packet[offset + 15]);
            found = true;
        }
        if ((packet[offset + 17] & 0xff) == 0x08) {
            caseLevel = batteryLevel(packet[offset + 19], packet[offset + 20]);
            caseCharging = charging(packet[offset + 20]);
            found = true;
        }
        if (!found) return null;
        return new AirPodsBatteryStore.Snapshot(
                name, left, right, caseLevel, previous.combined, System.currentTimeMillis(),
                "AirPods AACP", "Live battery status received from AirPods.",
                previous.leftInEar, previous.rightInEar, previous.lidState,
                leftCharging, rightCharging, caseCharging,
                previous.model, previous.microphone, previous.inCaseState,
                previous.combinedUpdatedAt, previous.combinedSource
        );
    }

    private static int batteryLevel(byte level, byte status) {
        int state = status & 0xff;
        if (state == 4) return -1; // AirPods explicitly report this component as disconnected.
        int value = level & 0xff;
        return value <= 100 ? value : -1;
    }

    private static int charging(byte status) {
        int state = status & 0xff;
        return state == 1 || state == 5 ? 1 : state == 2 ? 0 : -1;
    }

    private static int findPrefix(byte[] data, int from, int end) {
        for (int i = from; i + BATTERY_PREFIX.length <= end; i++) {
            boolean matches = true;
            for (int j = 0; j < BATTERY_PREFIX.length; j++) {
                if (data[i + j] != BATTERY_PREFIX[j]) { matches = false; break; }
            }
            if (matches) return i;
        }
        return -1;
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
