package com.hoonex.solarnet.bluetoothclassic;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class SolarBluetoothClassicBridge {
    private static final int MAX_MESSAGE_BYTES = 2 * 1024 * 1024;

    public interface Callback {
        void onOperationResult(long requestId, boolean success, String error);
        void onConnected(String connectionId, String deviceAddress, String deviceName);
        void onDisconnected(String connectionId);
        void onBytesReceived(String connectionId, byte[] payload);
        void onError(String operation, String message);
    }

    private interface SendCompletion {
        void complete(Throwable failure);
    }

    private final Object gate = new Object();
    private final Callback callback;
    private final BluetoothAdapter adapter;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Connection> connections = new HashMap<>();
    private final AtomicLong nextConnectionId = new AtomicLong();
    private volatile boolean serverRunning;
    private BluetoothServerSocket serverSocket;

    public SolarBluetoothClassicBridge(Activity activity, Callback callback) {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        if (callback == null) throw new IllegalArgumentException("callback is required");
        this.callback = callback;
        BluetoothManager manager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager == null ? null : manager.getAdapter();
    }

    public String[] getBondedDevices() {
        requireReady();
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        List<BluetoothDevice> devices = new ArrayList<>(bonded);
        Collections.sort(devices, Comparator.comparing(BluetoothDevice::getAddress));
        String[] result = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice device = devices.get(i);
            String name = device.getName();
            result[i] = device.getAddress() + "\n" + (name == null ? device.getAddress() : name);
        }
        return result;
    }

    public void startServer(final long requestId, final String serviceName, final String serviceUuid) {
        executor.execute(() -> {
            try {
                requireReady();
                UUID uuid = UUID.fromString(serviceUuid);
                synchronized (gate) {
                    if (serverRunning) throw new IllegalStateException("Bluetooth Classic server is already running");
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(serviceName, uuid);
                    serverRunning = true;
                }
                complete(requestId, true, null);
                acceptLoop();
            } catch (Throwable t) {
                stopServerOnly();
                complete(requestId, false, message(t));
            }
        });
    }

    public void connect(final long requestId, final String deviceAddress, final String serviceUuid) {
        executor.execute(() -> {
            BluetoothSocket socket = null;
            try {
                requireReady();
                BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
                socket = device.createRfcommSocketToServiceRecord(UUID.fromString(serviceUuid));
                socket.connect();
                register(socket);
                complete(requestId, true, null);
            } catch (Throwable t) {
                closeQuietly(socket);
                complete(requestId, false, message(t));
            }
        });
    }

    /**
     * Enqueue in caller order. The Connection serial queue guarantees that two application messages
     * cannot overtake each other just because the shared executor starts their workers out of order.
     */
    public void sendBytes(final long requestId, final String connectionId, final byte[] payload) {
        Connection connection;
        synchronized (gate) { connection = connections.get(connectionId); }
        if (connection == null) {
            executor.execute(() -> complete(requestId, false, "Unknown Bluetooth connection: " + connectionId));
            return;
        }
        connection.enqueueSend(payload, failure ->
                complete(requestId, failure == null, failure == null ? null : message(failure)));
    }

    public void broadcastBytes(final long requestId, final byte[] payload) {
        List<Connection> snapshot;
        synchronized (gate) { snapshot = new ArrayList<>(connections.values()); }
        if (snapshot.isEmpty()) {
            executor.execute(() -> complete(requestId, true, null));
            return;
        }

        AtomicInteger remaining = new AtomicInteger(snapshot.size());
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        for (Connection connection : snapshot) {
            connection.enqueueSend(payload, failure -> {
                if (failure != null) firstFailure.compareAndSet(null, failure);
                if (remaining.decrementAndGet() == 0) {
                    Throwable error = firstFailure.get();
                    complete(requestId, error == null, error == null ? null : message(error));
                }
            });
        }
    }

    public void disconnect(final long requestId, final String connectionId) {
        executor.execute(() -> {
            Connection connection;
            synchronized (gate) { connection = connections.remove(connectionId); }
            if (connection != null) connection.close();
            complete(requestId, true, null);
        });
    }

    public void stopAll(final long requestId) {
        executor.execute(() -> {
            stopServerOnly();
            List<Connection> snapshot;
            synchronized (gate) {
                snapshot = new ArrayList<>(connections.values());
                connections.clear();
            }
            for (Connection connection : snapshot) connection.close();
            complete(requestId, true, null);
        });
    }

    private void acceptLoop() {
        while (serverRunning) {
            BluetoothServerSocket current;
            synchronized (gate) { current = serverSocket; }
            if (current == null) return;
            try {
                BluetoothSocket socket = current.accept();
                if (socket != null) register(socket);
            } catch (IOException e) {
                if (serverRunning) callback.onError("accept", message(e));
                return;
            } catch (Throwable t) {
                if (serverRunning) callback.onError("accept", message(t));
                return;
            }
        }
    }

    private void register(BluetoothSocket socket) throws IOException {
        String connectionId = "btc-" + nextConnectionId.incrementAndGet();
        Connection connection = new Connection(connectionId, socket);
        synchronized (gate) { connections.put(connectionId, connection); }
        BluetoothDevice remote = socket.getRemoteDevice();
        String name = remote.getName();
        callback.onConnected(connectionId, remote.getAddress(), name == null ? remote.getAddress() : name);
        executor.execute(connection::readLoop);
    }

    private void remove(Connection connection) {
        boolean removed;
        synchronized (gate) { removed = connections.remove(connection.id) != null; }
        connection.close();
        if (removed) callback.onDisconnected(connection.id);
    }

    private void stopServerOnly() {
        BluetoothServerSocket socket;
        synchronized (gate) {
            serverRunning = false;
            socket = serverSocket;
            serverSocket = null;
        }
        closeQuietly(socket);
    }

    private void requireReady() {
        if (adapter == null) throw new IllegalStateException("Bluetooth is not supported on this device");
        if (!adapter.isEnabled()) throw new IllegalStateException("Bluetooth is disabled");
    }

    private void complete(long requestId, boolean success, String error) {
        callback.onOperationResult(requestId, success, error);
    }

    private static String message(Throwable t) {
        String message = t.getMessage();
        return message == null || message.length() == 0 ? t.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static void closeQuietly(BluetoothServerSocket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private final class Connection {
        final String id;
        final BluetoothSocket socket;
        final DataInputStream input;
        final DataOutputStream output;
        final SolarSerialTaskQueue sendQueue;
        volatile boolean closed;

        Connection(String id, BluetoothSocket socket) throws IOException {
            this.id = id;
            this.socket = socket;
            this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            this.sendQueue = new SolarSerialTaskQueue(executor);
        }

        void enqueueSend(byte[] payload, SendCompletion completion) {
            byte[] data = payload == null ? new byte[0] : payload.clone();
            if (data.length > MAX_MESSAGE_BYTES) {
                completion.complete(new IOException("Bluetooth message exceeds limit"));
                return;
            }
            boolean accepted = sendQueue.execute(() -> {
                Throwable failure = null;
                try {
                    sendNow(data);
                } catch (Throwable t) {
                    failure = t;
                }
                completion.complete(failure);
            });
            if (!accepted) completion.complete(new IOException("Bluetooth connection is closed"));
        }

        private synchronized void sendNow(byte[] payload) throws IOException {
            if (closed) throw new IOException("Bluetooth connection is closed");
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
        }

        void readLoop() {
            try {
                while (!closed) {
                    int length;
                    try { length = input.readInt(); }
                    catch (EOFException eof) { break; }
                    if (length < 0 || length > MAX_MESSAGE_BYTES) throw new IOException("Invalid Bluetooth message length: " + length);
                    byte[] payload = new byte[length];
                    input.readFully(payload);
                    callback.onBytesReceived(id, payload);
                }
            } catch (Throwable t) {
                if (!closed) callback.onError("read", message(t));
            } finally {
                remove(this);
            }
        }

        void close() {
            sendQueue.close();
            synchronized (this) {
                if (closed) return;
                closed = true;
                closeQuietly(socket);
            }
        }
    }
}
