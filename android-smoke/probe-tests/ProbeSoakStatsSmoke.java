package com.hoonex.solarnet.probe;

public final class ProbeSoakStatsSmoke {
    public static void main(String[] args) {
        cleanRunProducesDeterministicMetrics();
        malformedAndDuplicatePongsAreCounted();
        disconnectAndErrorBreakCleanResult();
        System.out.println("ProbeSoakStats smoke passed: 3/3");
    }

    private static void cleanRunProducesDeterministicMetrics() {
        ProbeSoakStats stats = new ProbeSoakStats("run-a", 1_000_000_000L);
        stats.recordSent(1, 1_100_000_000L);
        stats.recordSent(2, 2_100_000_000L);
        equal(ProbeSoakStats.PongResult.ACCEPTED,
                stats.recordPong("run-a", 1, 1_100_000_000L, 1_150_000_000L),
                "first pong");
        equal(ProbeSoakStats.PongResult.ACCEPTED,
                stats.recordPong("run-a", 2, 2_100_000_000L, 2_180_000_000L),
                "second pong");

        ProbeSoakStats.Snapshot snapshot = stats.snapshot(3_000_000_000L);
        equal(2L, snapshot.sent, "sent");
        equal(2L, snapshot.pong, "pong");
        equal(0L, snapshot.missing, "missing");
        equal(50.0, snapshot.minRttMillis(), 0.0001, "min rtt");
        equal(65.0, snapshot.averageRttMillis(), 0.0001, "avg rtt");
        equal(80.0, snapshot.maxRttMillis(), 0.0001, "max rtt");
        truth(snapshot.clean(), "clean run");
        contains(snapshot.toJson("completed"), "\"clean\":true", "json clean");
        contains(snapshot.toJson("completed"), "\"sent\":2", "json sent");
    }

    private static void malformedAndDuplicatePongsAreCounted() {
        ProbeSoakStats stats = new ProbeSoakStats("run-b", 0L);
        stats.recordSent(7, 100L);
        equal(ProbeSoakStats.PongResult.FOREIGN_RUN,
                stats.recordPong("other", 7, 100L, 120L),
                "foreign run");
        equal(ProbeSoakStats.PongResult.UNKNOWN_SEQUENCE,
                stats.recordPong("run-b", 99, 100L, 120L),
                "unknown sequence");
        equal(ProbeSoakStats.PongResult.SENT_TIMESTAMP_MISMATCH,
                stats.recordPong("run-b", 7, 101L, 120L),
                "timestamp mismatch");
        stats.recordInvalid();
        equal(ProbeSoakStats.PongResult.ACCEPTED,
                stats.recordPong("run-b", 7, 100L, 120L),
                "accepted pong");
        equal(ProbeSoakStats.PongResult.DUPLICATE,
                stats.recordPong("run-b", 7, 100L, 130L),
                "duplicate pong");

        ProbeSoakStats.Snapshot snapshot = stats.snapshot(200L);
        equal(4L, snapshot.invalid, "invalid count");
        equal(1L, snapshot.duplicate, "duplicate count");
        truth(!snapshot.clean(), "invalid run not clean");
    }

    private static void disconnectAndErrorBreakCleanResult() {
        ProbeSoakStats stats = new ProbeSoakStats("run-c", 0L);
        stats.recordSent(1, 10L);
        stats.recordPong("run-c", 1, 10L, 20L);
        stats.recordDisconnect();
        stats.recordError();
        ProbeSoakStats.Snapshot snapshot = stats.snapshot(30L);
        equal(1L, snapshot.disconnects, "disconnects");
        equal(1L, snapshot.errors, "errors");
        truth(!snapshot.clean(), "disconnect/error run not clean");
    }

    private static void truth(boolean value, String label) {
        if (!value) throw new AssertionError("Expected true: " + label);
    }

    private static void contains(String value, String fragment, String label) {
        if (!value.contains(fragment))
            throw new AssertionError(label + " expected fragment <" + fragment + "> in <" + value + ">");
    }

    private static void equal(long expected, long actual, String label) {
        if (expected != actual) throw new AssertionError(label + " expected " + expected + " got " + actual);
    }

    private static void equal(double expected, double actual, double tolerance, String label) {
        if (Math.abs(expected - actual) > tolerance)
            throw new AssertionError(label + " expected " + expected + " got " + actual);
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(label + " expected " + expected + " got " + actual);
    }
}
