package com.hoonex.solarnet.bluetoothclassic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

/**
 * Per-connection FIFO task scheduler backed by a shared executor.
 *
 * Tasks are enqueued synchronously in caller order, but only one task is submitted to the backing
 * executor at a time. This prevents a cached thread pool from reordering application messages that
 * must retain RFCOMM stream order (for example COMMIT before the TICK that makes it visible).
 * Closing rejects new tasks while allowing already-enqueued tasks to drain so their completion/error
 * callbacks are not silently lost.
 */
final class SolarSerialTaskQueue {
    private final Executor executor;
    private final Deque<Runnable> tasks = new ArrayDeque<>();
    private Runnable active;
    private boolean closed;

    SolarSerialTaskQueue(Executor executor) {
        if (executor == null) throw new IllegalArgumentException("executor is required");
        this.executor = executor;
    }

    synchronized boolean execute(Runnable task) {
        if (task == null) throw new IllegalArgumentException("task is required");
        if (closed) return false;
        tasks.addLast(() -> {
            try {
                task.run();
            } finally {
                scheduleNext();
            }
        });
        if (active == null) scheduleNext();
        return true;
    }

    synchronized void close() {
        closed = true;
    }

    synchronized int pendingCountForTest() {
        return tasks.size() + (active == null ? 0 : 1);
    }

    private synchronized void scheduleNext() {
        active = tasks.pollFirst();
        if (active != null) executor.execute(active);
    }
}
