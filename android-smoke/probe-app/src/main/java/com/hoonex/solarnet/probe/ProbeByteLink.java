package com.hoonex.solarnet.probe;

/**
 * Minimal byte-oriented link used by the physical transport soak controller.
 *
 * <p>The controller intentionally depends only on this contract so Bluetooth Classic and
 * Google Nearby are measured with the same framing, timer, sequence, and metric semantics.</p>
 */
public interface ProbeByteLink {
    void sendBytes(long requestId, String connectionId, byte[] payload);
    void broadcastBytes(long requestId, byte[] payload);
}
