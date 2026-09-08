package com.hoonex.solarnet.nearby;

import android.app.Activity;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SolarNearbyBridge {
    public interface Callback {
        void onOperationResult(long requestId, boolean success, String error);
        void onEndpointFound(String endpointId, String endpointName);
        void onEndpointLost(String endpointId);
        void onVerificationRequired(String endpointId, String endpointName, String authenticationDigits, boolean incoming);
        void onConnected(String endpointId);
        void onDisconnected(String endpointId);
        void onBytesReceived(String endpointId, byte[] payload);
        void onConnectionFailed(String endpointId, int statusCode);
        void onError(String operation, String message);
    }

    private final ConnectionsClient client;
    private final Callback callback;
    private final Set<String> connectedEndpointIds = ConcurrentHashMap.newKeySet();

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
            callback.onEndpointFound(endpointId, info.getEndpointName());
        }

        @Override
        public void onEndpointLost(String endpointId) {
            callback.onEndpointLost(endpointId);
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            if (payload.getType() != Payload.Type.BYTES) {
                callback.onError("payload", "SolarNet accepts Nearby BYTES payloads only.");
                return;
            }
            byte[] bytes = payload.asBytes();
            if (bytes == null) {
                callback.onError("payload", "Nearby returned a null BYTES payload.");
                return;
            }
            callback.onBytesReceived(endpointId, bytes);
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
            // BYTES payloads are complete in onPayloadReceived; no transfer-state buffering is needed.
        }
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo) {
            callback.onVerificationRequired(
                endpointId,
                connectionInfo.getEndpointName(),
                connectionInfo.getAuthenticationDigits(),
                connectionInfo.isIncomingConnection());
        }

        @Override
        public void onConnectionResult(String endpointId, ConnectionResolution result) {
            int statusCode = result.getStatus().getStatusCode();
            if (statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpointIds.add(endpointId);
                callback.onConnected(endpointId);
            } else {
                connectedEndpointIds.remove(endpointId);
                callback.onConnectionFailed(endpointId, statusCode);
            }
        }

        @Override
        public void onDisconnected(String endpointId) {
            connectedEndpointIds.remove(endpointId);
            callback.onDisconnected(endpointId);
        }
    };

    public SolarNearbyBridge(Activity activity, Callback callback) {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        if (callback == null) throw new IllegalArgumentException("callback is required");
        this.callback = callback;
        this.client = Nearby.getConnectionsClient(activity);
    }

    public void startAdvertising(long requestId, String serviceId, String endpointName, String strategyName) {
        AdvertisingOptions options = new AdvertisingOptions.Builder()
            .setStrategy(resolveStrategy(strategyName))
            .build();
        client.startAdvertising(endpointName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener(unused -> complete(requestId))
            .addOnFailureListener(error -> fail(requestId, error));
    }

    public void startDiscovery(long requestId, String serviceId, String endpointName, String strategyName) {
        DiscoveryOptions options = new DiscoveryOptions.Builder()
            .setStrategy(resolveStrategy(strategyName))
            .build();
        client.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener(unused -> complete(requestId))
            .addOnFailureListener(error -> fail(requestId, error));
    }

    public void stopAdvertising(long requestId) {
        client.stopAdvertising();
        complete(requestId);
    }

    public void stopDiscovery(long requestId) {
        client.stopDiscovery();
        complete(requestId);
    }

    public void requestConnection(long requestId, String endpointId, String endpointName) {
        client.requestConnection(endpointName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener(unused -> complete(requestId))
            .addOnFailureListener(error -> fail(requestId, error));
    }

    public void acceptConnection(long requestId, String endpointId) {
        client.acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener(unused -> complete(requestId))
            .addOnFailureListener(error -> fail(requestId, error));
    }

    public void rejectConnection(long requestId, String endpointId) {
        client.rejectConnection(endpointId)
            .addOnSuccessListener(unused -> complete(requestId))
            .addOnFailureListener(error -> fail(requestId, error));
    }

    public void disconnect(long requestId, String endpointId) {
        client.disconnectFromEndpoint(endpointId);
        connectedEndpointIds.remove(endpointId);
        complete(requestId);
    }

    public void sendBytes(long requestId, String endpointId, byte[] bytes) {
        if (!validateBytes(requestId, bytes)) return;
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnSuccessListener(unused -> complete(requestId))
            .addOnFailureListener(error -> fail(requestId, error));
    }

    public void broadcastBytes(long requestId, byte[] bytes) {
        if (!validateBytes(requestId, bytes)) return;
        ArrayList<String> endpoints = new ArrayList<>(connectedEndpointIds);
        if (endpoints.isEmpty()) {
            complete(requestId);
            return;
        }
        client.sendPayload(endpoints, Payload.fromBytes(bytes))
            .addOnSuccessListener(unused -> complete(requestId))
            .addOnFailureListener(error -> fail(requestId, error));
    }

    public void stopAll(long requestId) {
        client.stopAdvertising();
        client.stopDiscovery();
        client.stopAllEndpoints();
        connectedEndpointIds.clear();
        complete(requestId);
    }

    private boolean validateBytes(long requestId, byte[] bytes) {
        if (bytes == null) {
            callback.onOperationResult(requestId, false, "Nearby BYTES payload is null.");
            return false;
        }
        if (bytes.length > ConnectionsClient.MAX_BYTES_DATA_SIZE) {
            callback.onOperationResult(requestId, false, "Nearby BYTES payload exceeds MAX_BYTES_DATA_SIZE.");
            return false;
        }
        return true;
    }

    private Strategy resolveStrategy(String name) {
        if ("CLUSTER".equals(name)) return Strategy.P2P_CLUSTER;
        if ("POINT_TO_POINT".equals(name)) return Strategy.P2P_POINT_TO_POINT;
        return Strategy.P2P_STAR;
    }

    private void complete(long requestId) {
        callback.onOperationResult(requestId, true, null);
    }

    private void fail(long requestId, Exception error) {
        String message = error == null ? "Nearby operation failed." : error.getClass().getSimpleName() + ": " + error.getMessage();
        callback.onOperationResult(requestId, false, message);
    }
}
