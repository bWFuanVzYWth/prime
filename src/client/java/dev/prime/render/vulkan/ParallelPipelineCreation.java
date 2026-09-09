// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import dev.prime.infrastructure.PrimeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Bounded, task-local host concurrency for independent Vulkan pipeline creation. */
public final class ParallelPipelineCreation {
    private static final int MAX_WORKERS = 16;

    private ParallelPipelineCreation() {
    }

    public static void run(String label, int count, IntConsumer creation) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(creation, "creation");
        if (count < 0) {
            throw new IllegalArgumentException("Pipeline count must be non-negative");
        }
        if (count == 0) {
            return;
        }
        int workers = workerCount(count, Runtime.getRuntime().availableProcessors());
        long start = System.nanoTime();
        if (workers == 1) {
            for (int index = 0; index < count; index++) {
                creation.accept(index);
            }
        } else {
            runWorkers(label, count, workers, creation);
        }
        PrimeInfo.LOGGER.info(
                "{} created {} pipeline(s) in {} ms using {} host thread(s)",
                label,
                count,
                (System.nanoTime() - start) / 1_000_000L,
                workers);
    }

    static int workerCount(int count, int availableProcessors) {
        if (count < 0) {
            throw new IllegalArgumentException("Pipeline count must be non-negative");
        }
        return Math.min(count, Math.min(MAX_WORKERS, Math.max(availableProcessors, 1)));
    }

    static void runWorkers(
            String label, int count, int workers, IntConsumer creation) {
        AtomicInteger threadIndex = new AtomicInteger();
        ThreadFactory threads = task -> new Thread(
                task,
                "Prime " + label + " compiler " + threadIndex.incrementAndGet());
        ExecutorService executor = Executors.newFixedThreadPool(workers, threads);
        List<Future<?>> futures = new ArrayList<>(count);
        try {
            for (int index = 0; index < count; index++) {
                int pipelineIndex = index;
                futures.add(executor.submit(() -> creation.accept(pipelineIndex)));
            }
            join(futures);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void join(List<Future<?>> futures) {
        RuntimeException failure = null;
        boolean interrupted = false;
        for (Future<?> future : futures) {
            for (;;) {
                try {
                    future.get();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    RuntimeException current = cause instanceof RuntimeException runtime
                            ? runtime
                            : new IllegalStateException("Parallel pipeline creation failed", cause);
                    if (failure == null) {
                        failure = current;
                    } else {
                        failure.addSuppressed(current);
                    }
                    break;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            IllegalStateException interruption =
                    new IllegalStateException("Parallel pipeline creation was interrupted");
            if (failure != null) {
                interruption.addSuppressed(failure);
            }
            throw interruption;
        }
        if (failure != null) {
            throw failure;
        }
    }
}
