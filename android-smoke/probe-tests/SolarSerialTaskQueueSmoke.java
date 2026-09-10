package com.hoonex.solarnet.bluetoothclassic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SolarSerialTaskQueueSmoke {
    public static void main(String[] args) throws Exception {
        cachedPoolCannotOvertakeQueuedMessages();
        closeRejectsNewButDrainsAlreadyQueuedTasks();
        System.out.println("Bluetooth FIFO send queue smoke: PASS");
    }

    private static void cachedPoolCannotOvertakeQueuedMessages() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            SolarSerialTaskQueue queue = new SolarSerialTaskQueue(pool);
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(3);

            require(queue.execute(() -> {
                firstStarted.countDown();
                await(releaseFirst);
                order.add("COMMIT");
                completed.countDown();
            }), "first enqueue rejected");
            require(firstStarted.await(2, TimeUnit.SECONDS), "first task did not start");

            require(queue.execute(() -> {
                order.add("TICK");
                completed.countDown();
            }), "second enqueue rejected");
            require(queue.execute(() -> {
                order.add("DIGEST");
                completed.countDown();
            }), "third enqueue rejected");

            Thread.sleep(80L);
            require(order.isEmpty(), "later messages overtook blocked COMMIT: " + order);
            releaseFirst.countDown();
            require(completed.await(2, TimeUnit.SECONDS), "queued tasks did not drain");
            require(order.size() == 3, "wrong completion count");
            require("COMMIT".equals(order.get(0)), "COMMIT was not first: " + order);
            require("TICK".equals(order.get(1)), "TICK was not second: " + order);
            require("DIGEST".equals(order.get(2)), "DIGEST was not third: " + order);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void closeRejectsNewButDrainsAlreadyQueuedTasks() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            SolarSerialTaskQueue queue = new SolarSerialTaskQueue(pool);
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch drained = new CountDownLatch(2);

            require(queue.execute(() -> {
                firstStarted.countDown();
                await(releaseFirst);
                drained.countDown();
            }), "first enqueue rejected");
            require(firstStarted.await(2, TimeUnit.SECONDS), "first task did not start");
            require(queue.execute(drained::countDown), "second enqueue rejected");

            queue.close();
            require(!queue.execute(() -> { }), "closed queue accepted a new task");
            releaseFirst.countDown();
            require(drained.await(2, TimeUnit.SECONDS), "pre-close queued tasks were discarded");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
