package com.hoonex.solarnet.probe;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ProbeSoakStats {
    public enum PongResult {
        ACCEPTED,
        DUPLICATE,
        FOREIGN_RUN,
        UNKNOWN_SEQUENCE,
        SENT_TIMESTAMP_MISMATCH
    }

    public static final class Snapshot {
        public final String runId;
        public final long startedNanos;
        public final long observedNanos;
        public final long sent;
        public final long pong;
        public final long missing;
        public final long duplicate;
        public final long invalid;
        public final long disconnects;
        public final long errors;
        public final long minRttNanos;
        public final long maxRttNanos;
        public final long totalRttNanos;

        private Snapshot(
                String runId,
                long startedNanos,
                long observedNanos,
                long sent,
                long pong,
                long duplicate,
                long invalid,
                long disconnects,
                long errors,
                long minRttNanos,
                long maxRttNanos,
                long totalRttNanos) {
            this.runId = runId;
            this.startedNanos = startedNanos;
            this.observedNanos = observedNanos;
            this.sent = sent;
            this.pong = pong;
            this.missing = Math.max(0L, sent - pong);
            this.duplicate = duplicate;
            this.invalid = invalid;
            this.disconnects = disconnects;
            this.errors = errors;
            this.minRttNanos = pong == 0 ? 0L : minRttNanos;
            this.maxRttNanos = pong == 0 ? 0L : maxRttNanos;
            this.totalRttNanos = totalRttNanos;
        }

        public double elapsedSeconds() {
            return Math.max(0L, observedNanos - startedNanos) / 1_000_000_000.0;
        }

        public double averageRttMillis() {
            return pong == 0 ? 0.0 : (totalRttNanos / (double) pong) / 1_000_000.0;
        }

        public double minRttMillis() {
            return minRttNanos / 1_000_000.0;
        }

        public double maxRttMillis() {
            return maxRttNanos / 1_000_000.0;
        }

        public double lossPercent() {
            return sent == 0 ? 0.0 : (missing * 100.0) / sent;
        }

        public boolean clean() {
            return sent > 0 && missing == 0 && duplicate == 0 && invalid == 0 && disconnects == 0 && errors == 0;
        }

        public String toSummary() {
            return String.format(
                    Locale.US,
                    "run=%s sent=%d pong=%d missing=%d loss=%.2f%% dup=%d invalid=%d disconnect=%d errors=%d rtt(ms)=%.1f/%.1f/%.1f elapsed=%.1fs",
                    runId,
                    sent,
                    pong,
                    missing,
                    lossPercent(),
                    duplicate,
                    invalid,
                    disconnects,
                    errors,
                    minRttMillis(),
                    averageRttMillis(),
                    maxRttMillis(),
                    elapsedSeconds());
        }

        public String toJson(String completionReason) {
            return "{" +
                    "\"schema\":1," +
                    "\"runId\":\"" + json(runId) + "\"," +
                    "\"completionReason\":\"" + json(completionReason == null ? "" : completionReason) + "\"," +
                    "\"clean\":" + clean() + "," +
                    "\"elapsedSeconds\":" + decimal(elapsedSeconds()) + "," +
                    "\"sent\":" + sent + "," +
                    "\"pong\":" + pong + "," +
                    "\"missing\":" + missing + "," +
                    "\"lossPercent\":" + decimal(lossPercent()) + "," +
                    "\"duplicate\":" + duplicate + "," +
                    "\"invalid\":" + invalid + "," +
                    "\"disconnects\":" + disconnects + "," +
                    "\"errors\":" + errors + "," +
                    "\"minRttMs\":" + decimal(minRttMillis()) + "," +
                    "\"avgRttMs\":" + decimal(averageRttMillis()) + "," +
                    "\"maxRttMs\":" + decimal(maxRttMillis()) +
                    "}";
        }

        private static String json(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String decimal(double value) {
            return String.format(Locale.US, "%.3f", value);
        }
    }

    private final String runId;
    private final long startedNanos;
    private final Map<Long, Long> sentBySequence = new HashMap<>();
    private final Set<Long> acceptedSequences = new HashSet<>();
    private long sent;
    private long pong;
    private long duplicate;
    private long invalid;
    private long disconnects;
    private long errors;
    private long minRttNanos = Long.MAX_VALUE;
    private long maxRttNanos;
    private long totalRttNanos;

    public ProbeSoakStats(String runId, long startedNanos) {
        if (runId == null || runId.trim().isEmpty()) throw new IllegalArgumentException("runId is required");
        if (startedNanos < 0) throw new IllegalArgumentException("startedNanos cannot be negative");
        this.runId = runId;
        this.startedNanos = startedNanos;
    }

    public synchronized String runId() {
        return runId;
    }

    public synchronized long startedNanos() {
        return startedNanos;
    }

    public synchronized void recordSent(long sequence, long sentNanos) {
        if (sequence < 0) throw new IllegalArgumentException("sequence cannot be negative");
        if (sentNanos < startedNanos) throw new IllegalArgumentException("sent timestamp precedes soak start");
        if (sentBySequence.containsKey(sequence)) throw new IllegalArgumentException("sequence already sent: " + sequence);
        sentBySequence.put(sequence, sentNanos);
        sent++;
    }

    public synchronized PongResult recordPong(
            String pongRunId,
            long sequence,
            long echoedSentNanos,
            long receivedNanos) {
        if (!runId.equals(pongRunId)) {
            invalid++;
            return PongResult.FOREIGN_RUN;
        }
        Long expectedSent = sentBySequence.get(sequence);
        if (expectedSent == null) {
            invalid++;
            return PongResult.UNKNOWN_SEQUENCE;
        }
        if (acceptedSequences.contains(sequence)) {
            duplicate++;
            return PongResult.DUPLICATE;
        }
        if (expectedSent.longValue() != echoedSentNanos) {
            invalid++;
            return PongResult.SENT_TIMESTAMP_MISMATCH;
        }
        if (receivedNanos < echoedSentNanos) {
            invalid++;
            return PongResult.SENT_TIMESTAMP_MISMATCH;
        }

        acceptedSequences.add(sequence);
        pong++;
        long rtt = receivedNanos - echoedSentNanos;
        minRttNanos = Math.min(minRttNanos, rtt);
        maxRttNanos = Math.max(maxRttNanos, rtt);
        totalRttNanos += rtt;
        return PongResult.ACCEPTED;
    }

    public synchronized void recordInvalid() {
        invalid++;
    }

    public synchronized void recordDisconnect() {
        disconnects++;
    }

    public synchronized void recordError() {
        errors++;
    }

    public synchronized Snapshot snapshot(long observedNanos) {
        if (observedNanos < startedNanos) observedNanos = startedNanos;
        return new Snapshot(
                runId,
                startedNanos,
                observedNanos,
                sent,
                pong,
                duplicate,
                invalid,
                disconnects,
                errors,
                minRttNanos,
                maxRttNanos,
                totalRttNanos);
    }
}
