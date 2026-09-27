package dev.primept.capture;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SynchronousWorkersTest {
    private record Workspace(int index, AtomicInteger closed) implements AutoCloseable {
        public void close() {
            closed.incrementAndGet();
        }
    }
    @Test
    void stableResultsPrivateReusableWorkspacesAndInlineSmallBatches() {
        var created = new AtomicInteger();
        var closed = new AtomicInteger();
        int[] parallel = new int[10000], serial = new int[10000];
        try (var workers = new SynchronousWorkers<>(4, index -> {
                 created.incrementAndGet();
                 return new Workspace(index, closed);
             })) {
            Thread owner = Thread.currentThread();
            workers.run(3, 32,
                        (workspace, first, end) -> assertSame(owner, Thread.currentThread()));
            assertEquals(1, created.get());
            for (int repeat = 0; repeat < 3; repeat++)
                workers.run(parallel.length, 32, (workspace, first, end) -> {
                    for (int i = first; i < end; i++)
                        parallel[i] = i * 31;
                });
            assertEquals(4, created.get());
            for (int i = 0; i < serial.length; i++)
                serial[i] = i * 31;
            assertArrayEquals(serial, parallel);
        }
        assertEquals(created.get(), closed.get());
    }
    @Test
    void errorAndInterruptionStillJoinEverySubmittedTask() throws Exception {
        var entered = new CountDownLatch(4);
        var active = new AtomicInteger();
        var completed = new AtomicInteger();
        try (var workers =
                     new SynchronousWorkers<>(4, i -> new Workspace(i, new AtomicInteger()))) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(IllegalStateException.class,
                             () -> workers.run(40, 1, (workspace, first, end) -> {
                                 active.incrementAndGet();
                                 entered.countDown();
                                 try {
                                     if (!entered.await(5, TimeUnit.SECONDS))
                                         throw new AssertionError("Missing worker");
                                     if (workspace.index == 0)
                                         throw new IllegalStateException("Injected failure");
                                     completed.incrementAndGet();
                                 } catch (InterruptedException error) {
                                     throw new AssertionError(error);
                                 } finally {
                                     active.decrementAndGet();
                                 }
                             }));
                assertEquals(0, active.get());
                assertEquals(3, completed.get());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            workers.run(40, 1, (workspace, first, end) -> completed.addAndGet(end - first));
            assertEquals(43, completed.get());
        }
    }
    @Test
    void widePartitionArithmeticDoesNotOverflow() {
        var count = new java.util.concurrent.atomic.AtomicLong();
        try (var workers =
                     new SynchronousWorkers<>(4, i -> new Workspace(i, new AtomicInteger()))) {
            workers.run(Integer.MAX_VALUE, 1, (workspace, first, end) -> {
                assertTrue(first >= 0 && end > first);
                count.addAndGet(end - (long)first);
            });
        }
        assertEquals(Integer.MAX_VALUE, count.get());
    }
}
