package com.hoonex.solarnet.probe;

import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class ProbeSoakController implements AutoCloseable {
    public interface Listener {
        void onProgress(ProbeSoakStats.Snapshot snapshot);
        void onFinished(ProbeSoakStats.Snapshot snapshot, String reason);
        void onLog(String message);
    }

    public static final long DEFAULT_DURATION_MS = 10L * 60L * 1000L;
    public static final long DEFAULT_INTERVAL_MS = 1000L;

    private static final String PREFIX = "SNP1";
    private static final String PING = "PING";
    private static final String PONG = "PONG";

    private final Object gate = new Object();
    private final ProbeByteLink link;
    private final LongSupplier nextRequestId;
    private final Listener listener;
    private final ScheduledExecutorService scheduler;
    private final Set<Long> clientPingOperationIds = Collections.synchronizedSet(new HashSet<>());
    private final Set<Long> hostPongOperationIds = Collections.synchronizedSet(new HashSet<>());

    private ProbeSoakStats stats;
    private ScheduledFuture<?> scheduled;
    private long durationNanos;
    private long nextSequence;

    public ProbeSoakController(
            ProbeByteLink link,
            LongSupplier nextRequestId,
            Listener listener) {
        if (link == null) throw new IllegalArgumentException("link is required");
        if (nextRequestId == null) throw new IllegalArgumentException("nextRequestId is required");
        if (listener == null) throw new IllegalArgumentException("listener is required");
        this.link = link;
        this.nextRequestId = nextRequestId;
        this.listener = listener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "solarnet-probe-soak");
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean isRunning() {
        synchronized (gate) {
            return stats != null;
        }
    }

    public String start() {
        return start(DEFAULT_DURATION_MS, DEFAULT_INTERVAL_MS);
    }

    public String start(long durationMs, long intervalMs) {
        if (durationMs <= 0) throw new IllegalArgumentException("durationMs must be positive");
        if (intervalMs < 100) throw new IllegalArgumentException("intervalMs must be at least 100 ms");

        final ProbeSoakStats created;
        synchronized (gate) {
            if (stats != null) throw new IllegalStateException("A soak run is already active.");
            long now = SystemClock.elapsedRealtimeNanos();
            created = new ProbeSoakStats(UUID.randomUUID().toString(), now);
            stats = created;
            durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
            nextSequence = 0L;
            scheduled = scheduler.scheduleAtFixedRate(this::tick, 0L, intervalMs, TimeUnit.MILLISECONDS);
        }
        listener.onLog("SOAK START run=" + created.runId() + " durationMs=" + durationMs + " intervalMs=" + intervalMs);
        return created.runId();
    }

    public void stop(String reason) {
        finish(reason == null || reason.trim().isEmpty() ? "stopped" : reason);
    }

    /**
     * Returns true when the operation belongs to the soak protocol and should not be duplicated
     * into the Activity's per-operation UI log.
     */
    public boolean onOperationResult(long requestId, boolean success, String error) {
        if (clientPingOperationIds.remove(requestId)) {
            if (!success) {
                ProbeSoakStats current = currentStats();
                if (current != null) {
                    current.recordError();
                    listener.onProgress(current.snapshot(SystemClock.elapsedRealtimeNanos()));
                }
                listener.onLog("SOAK PING send failed: " + safe(error));
            }
            return true;
        }

        if (hostPongOperationIds.remove(requestId)) {
            if (!success) listener.onLog("SOAK PONG echo failed: " + safe(error));
            return true;
        }

        return false;
    }

    public void onDisconnected() {
        ProbeSoakStats current = currentStats();
        if (current == null) return;
        current.recordDisconnect();
        listener.onProgress(current.snapshot(SystemClock.elapsedRealtimeNanos()));
    }

    public void onBridgeError(String operation, String error) {
        ProbeSoakStats current = currentStats();
        if (current == null) return;
        current.recordError();
        listener.onLog("SOAK bridge error " + operation + ": " + safe(error));
        listener.onProgress(current.snapshot(SystemClock.elapsedRealtimeNanos()));
    }

    public boolean handleBytes(String connectionId, byte[] payload, boolean hostMode) {
        if (payload == null || payload.length == 0) return false;
        String text = new String(payload, StandardCharsets.UTF_8);
        if (!text.startsWith(PREFIX + "|")) return false;

        String[] parts = text.split("\\|", -1);
        if (parts.length != 5 || !PREFIX.equals(parts[0])) {
            recordMalformed("field-count", text);
            return true;
        }

        long sequence;
        long sentNanos;
        try {
            sequence = Long.parseLong(parts[3]);
            sentNanos = Long.parseLong(parts[4]);
        } catch (NumberFormatException ex) {
            recordMalformed("number", text);
            return true;
        }

        if (PING.equals(parts[1])) {
            if (!hostMode) return true;
            String pong = encode(PONG, parts[2], sequence, sentNanos);
            long requestId = nextRequestId.getAsLong();
            hostPongOperationIds.add(requestId);
            try {
                link.sendBytes(requestId, connectionId, pong.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable throwable) {
                hostPongOperationIds.remove(requestId);
                listener.onLog("SOAK host PONG send failed: " + message(throwable));
            }
            return true;
        }

        if (PONG.equals(parts[1])) {
            ProbeSoakStats current = currentStats();
            if (current == null) return true;
            ProbeSoakStats.PongResult result = current.recordPong(
                    parts[2],
                    sequence,
                    sentNanos,
                    SystemClock.elapsedRealtimeNanos());
            if (result != ProbeSoakStats.PongResult.ACCEPTED)
                listener.onLog("SOAK PONG rejected: " + result + " seq=" + sequence);
            if (result != ProbeSoakStats.PongResult.ACCEPTED || sequence % 5L == 0L)
                listener.onProgress(current.snapshot(SystemClock.elapsedRealtimeNanos()));
            return true;
        }

        recordMalformed("type", text);
        return true;
    }

    private void tick() {
        ProbeSoakStats current;
        long sequence;
        long now = SystemClock.elapsedRealtimeNanos();
        synchronized (gate) {
            current = stats;
            if (current == null) return;
            if (now - current.startedNanos() >= durationNanos) {
                sequence = -1L;
            } else {
                sequence = ++nextSequence;
            }
        }

        if (sequence < 0L) {
            finish("completed");
            return;
        }

        long requestId = -1L;
        try {
            current.recordSent(sequence, now);
            requestId = nextRequestId.getAsLong();
            clientPingOperationIds.add(requestId);
            String ping = encode(PING, current.runId(), sequence, now);
            link.broadcastBytes(requestId, ping.getBytes(StandardCharsets.UTF_8));
            if (sequence % 5L == 0L)
                listener.onProgress(current.snapshot(SystemClock.elapsedRealtimeNanos()));
        } catch (Throwable throwable) {
            if (requestId >= 0L) clientPingOperationIds.remove(requestId);
            current.recordError();
            listener.onLog("SOAK tick error: " + message(throwable));
            listener.onProgress(current.snapshot(SystemClock.elapsedRealtimeNanos()));
        }
    }

    private void recordMalformed(String reason, String payload) {
        ProbeSoakStats current = currentStats();
        if (current != null) {
            current.recordInvalid();
            listener.onProgress(current.snapshot(SystemClock.elapsedRealtimeNanos()));
        }
        listener.onLog("SOAK malformed packet (" + reason + "): " + payload);
    }

    private ProbeSoakStats currentStats() {
        synchronized (gate) {
            return stats;
        }
    }

    private void finish(String reason) {
        ProbeSoakStats finished;
        ScheduledFuture<?> future;
        synchronized (gate) {
            finished = stats;
            if (finished == null) return;
            stats = null;
            future = scheduled;
            scheduled = null;
        }
        if (future != null) future.cancel(false);
        clientPingOperationIds.clear();
        hostPongOperationIds.clear();
        ProbeSoakStats.Snapshot snapshot = finished.snapshot(SystemClock.elapsedRealtimeNanos());
        listener.onProgress(snapshot);
        listener.onFinished(snapshot, reason);
    }

    private static String encode(String type, String runId, long sequence, long sentNanos) {
        return PREFIX + "|" + type + "|" + runId + "|" + sequence + "|" + sentNanos;
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isEmpty() ? throwable.getClass().getSimpleName() : value;
    }

    @Override
    public void close() {
        finish("controller-closed");
        scheduler.shutdownNow();
    }
}
