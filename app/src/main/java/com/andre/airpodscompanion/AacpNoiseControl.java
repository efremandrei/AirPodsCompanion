package com.andre.airpodscompanion;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.ParcelUuid;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

final class AacpNoiseControl {
    static final int MODE_OFF = 0x01;
    static final int MODE_ANC = 0x02;
    static final int MODE_TRANSPARENCY = 0x03;
    static final int MODE_ADAPTIVE = 0x04;

    private static final int AACP_PSM = 0x1001;
    private static final int TYPE_L2CAP = 3;
    private static final UUID AACP_RAW_UUID = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");
    private static final ParcelUuid AACP_UUID = new ParcelUuid(AACP_RAW_UUID);
    private static final byte[] AACP_CONNECT = hex("00000400010002000000000000000000");
    private static final byte[] AACP_INIT = hex("040004004D00D700000000000000");

    private AacpNoiseControl() {
    }

    @SuppressLint("MissingPermission")
    static void setMode(BluetoothDevice device, int mode) throws Exception {
        SocketCandidate candidate = openAacpSocket(device);
        BluetoothSocket socket = candidate.socket;
        try {
            socket.connect();
            OutputStream output = socket.getOutputStream();
            write(output, AACP_CONNECT);
            sleepQuietly(250L);
            write(output, AACP_INIT);
            sleepQuietly(100L);
            write(output, noiseModePacket(mode));
            sleepQuietly(150L);
        } catch (IOException error) {
            throw new IOException(candidate.label + ": " + error.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // The command has already been sent.
            }
        }
    }

    private static SocketCandidate openAacpSocket(BluetoothDevice device) throws Exception {
        String[] methods = {
                "createInsecureL2capSocket",
                "createL2capSocket"
        };
        Exception lastError = null;
        for (String name : methods) {
            try {
                Method method = findMethod(device.getClass(), name);
                Object socket = method.invoke(device, AACP_PSM);
                if (socket instanceof BluetoothSocket) {
                    return new SocketCandidate((BluetoothSocket) socket, name);
                }
            } catch (Exception error) {
                lastError = error;
            }
        }

        SocketCandidate constructorSocket = tryBluetoothSocketConstructors(device);
        if (constructorSocket != null) {
            return constructorSocket;
        }

        BluetoothSocket publicSocket = trySocketSettings(device);
        if (publicSocket != null) {
            return new SocketCandidate(publicSocket, "createUsingSocketSettings");
        }
        throw new IOException("AACP L2CAP socket API is unavailable", lastError);
    }

    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        try {
            Method method = type.getMethod(name, int.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
            Method method = type.getDeclaredMethod(name, int.class);
            method.setAccessible(true);
            return method;
        }
    }

    private static BluetoothSocket trySocketSettings(BluetoothDevice device) throws Exception {
        try {
            Class<?> settingsClass = Class.forName("android.bluetooth.BluetoothSocketSettings");
            Class<?> builderClass = Class.forName("android.bluetooth.BluetoothSocketSettings$Builder");
            Object builder = builderClass.getDeclaredConstructor().newInstance();
            builderClass.getMethod("setSocketType", int.class).invoke(builder, TYPE_L2CAP);
            builderClass.getMethod("setL2capPsm", int.class).invoke(builder, AACP_PSM);
            builderClass.getMethod("setAuthenticationRequired", boolean.class).invoke(builder, false);
            builderClass.getMethod("setEncryptionRequired", boolean.class).invoke(builder, false);
            Object settings = builderClass.getMethod("build").invoke(builder);
            Method create = BluetoothDevice.class.getMethod("createUsingSocketSettings", settingsClass);
            Object socket = create.invoke(device, settings);
            return socket instanceof BluetoothSocket ? (BluetoothSocket) socket : null;
        } catch (ClassNotFoundException | NoSuchMethodException error) {
            return null;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof SecurityException) {
                throw (SecurityException) cause;
            }
            return null;
        }
    }

    private static SocketCandidate tryBluetoothSocketConstructors(BluetoothDevice device) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        Object[][] signatures = new Object[][]{
                new Object[]{TYPE_L2CAP, -1, true, true, device, AACP_PSM, AACP_UUID},
                new Object[]{TYPE_L2CAP, -1, false, true, device, AACP_PSM, AACP_UUID},
                new Object[]{TYPE_L2CAP, -1, false, false, device, AACP_PSM, AACP_UUID},
                new Object[]{TYPE_L2CAP, -1, true, true, device, AACP_PSM, AACP_UUID, false},
                new Object[]{TYPE_L2CAP, -1, true, true, device, AACP_PSM, AACP_RAW_UUID},
                new Object[]{TYPE_L2CAP, -1, false, false, device, AACP_PSM, AACP_RAW_UUID},
                new Object[]{adapter, device, TYPE_L2CAP, true, true, AACP_PSM, AACP_UUID},
                new Object[]{device, TYPE_L2CAP, true, true, AACP_PSM, AACP_UUID},
                new Object[]{device, TYPE_L2CAP, 1, true, true, AACP_PSM, AACP_UUID},
                new Object[]{TYPE_L2CAP, 1, true, true, device, AACP_PSM, AACP_UUID},
                new Object[]{TYPE_L2CAP, true, true, device, AACP_PSM, AACP_UUID}
        };
        for (Object[] values : signatures) {
            try {
                Class<?>[] types = new Class<?>[values.length];
                for (int i = 0; i < values.length; i++) {
                    types[i] = primitiveType(values[i]);
                }
                Constructor<BluetoothSocket> constructor = BluetoothSocket.class.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                return new SocketCandidate(constructor.newInstance(values), "BluetoothSocket constructor");
            } catch (Exception ignored) {
                // Try the next platform constructor shape.
            }
        }
        return null;
    }

    private static Class<?> primitiveType(Object value) {
        if (value instanceof Integer) {
            return int.class;
        }
        if (value instanceof Boolean) {
            return boolean.class;
        }
        if (value instanceof UUID) {
            return UUID.class;
        }
        return value.getClass();
    }

    private static void write(OutputStream output, byte[] packet) throws IOException {
        output.write(packet);
        output.flush();
    }

    private static byte[] noiseModePacket(int mode) {
        return new byte[]{
                0x04, 0x00,
                0x04, 0x00,
                0x09, 0x00,
                0x0D,
                (byte) mode,
                0x00, 0x00, 0x00
        };
    }

    private static byte[] hex(String value) {
        int length = value.length();
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
        }
        return bytes;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class SocketCandidate {
        final BluetoothSocket socket;
        final String label;

        SocketCandidate(BluetoothSocket socket, String label) {
            this.socket = socket;
            this.label = label;
        }
    }
}
