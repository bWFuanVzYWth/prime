// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;

final class ParallelPipelineCreationTest {
    @Test
    void workerCountIsBoundedByWorkHostAndCap() {
        assertEquals(0, ParallelPipelineCreation.workerCount(0, 32));
        assertEquals(1, ParallelPipelineCreation.workerCount(1, 32));
        assertEquals(2, ParallelPipelineCreation.workerCount(8, 2));
        assertEquals(8, ParallelPipelineCreation.workerCount(32, 8));
        assertEquals(16, ParallelPipelineCreation.workerCount(32, 32));
        assertEquals(1, ParallelPipelineCreation.workerCount(8, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ParallelPipelineCreation.workerCount(-1, 8));
    }

    @Test
    void workersVisitEveryIndexExactlyOnce() {
        AtomicIntegerArray visits = new AtomicIntegerArray(17);

        ParallelPipelineCreation.runWorkers(
                "test", visits.length(), 3, visits::incrementAndGet);

        for (int index = 0; index < visits.length(); index++) {
            assertEquals(1, visits.get(index));
        }
    }

    @Test
    void failureWaitsForOtherWorkersBeforeItEscapes() {
        CountDownLatch started = new CountDownLatch(2);
        AtomicBoolean secondCompleted = new AtomicBoolean();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ParallelPipelineCreation.runWorkers(
                        "test",
                        2,
                        2,
                        index -> {
                            started.countDown();
                            await(started);
                            if (index == 0) {
                                throw new IllegalStateException("expected failure");
                            }
                            secondCompleted.set(true);
                        }));

        assertEquals("expected failure", failure.getMessage());
        assertTrue(secondCompleted.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test worker was interrupted", exception);
        }
    }
}
