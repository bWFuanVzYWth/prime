package dev.primept.capture;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/** Explicit private workspaces; every submitted task is joined before returning, even on failure. */
public final class SynchronousWorkers<W extends AutoCloseable> implements AutoCloseable {
    @FunctionalInterface
    public interface Work<W> {
        void run(W workspace, int first, int end);
    }
    private final int threads;
    private final IntFunction<W> factory;
    private final ArrayList<W> workspaces = new ArrayList<>();
    private ExecutorService executor;

    public SynchronousWorkers(int threads, IntFunction<W> factory) {
        if (threads < 1)
            throw new IllegalArgumentException("Worker count must be positive");
        this.threads = threads;
        this.factory = factory;
    }

    public void run(int count, int minimumChunk, Work<W> work) {
        if (count < 0 || minimumChunk < 1)
            throw new IllegalArgumentException("Invalid batch size");
        if (count == 0)
            return;
        int tasks = Math.min(threads, Math.max(1, count / minimumChunk));
        // MC workspace construction stays on the owner thread.
        while (workspaces.size() < tasks)
            workspaces.add(factory.apply(workspaces.size()));
        if (tasks == 1) {
            work.run(workspaces.getFirst(), 0, count);
            return;
        }
        if (executor == null)
            executor = Executors.newFixedThreadPool(
                    threads, Thread.ofPlatform().name("prime-capture-", 0).factory());
        var futures = new ArrayList<Future<?>>(tasks);
        Throwable failure = null;
        try {
            for (int i = 0; i < tasks; i++) {
                int first = (int)((long)count * i / tasks),
                    end = (int)((long)count * (i + 1) / tasks);
                W workspace = workspaces.get(i);
                futures.add(executor.submit(() -> work.run(workspace, first, end)));
            }
        } catch (Throwable error) {
            failure = error;
        }
        boolean interrupted = false;
        for (Future<?> future : futures) {
            for (;;) {
                try {
                    future.get();
                    break;
                } catch (InterruptedException error) {
                    interrupted = true;
                } catch (ExecutionException error) {
                    if (failure == null)
                        failure = error.getCause();
                    break;
                }
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
        if (failure instanceof Error error)
            throw error;
        if (failure instanceof RuntimeException error)
            throw error;
        if (failure != null || interrupted)
            throw new IllegalStateException("Synchronous capture batch failed", failure);
    }

    @Override
    public void close() {
        if (executor != null)
            executor.close();
        RuntimeException failure = null;
        for (W workspace : workspaces) {
            try {
                workspace.close();
            } catch (Exception error) {
                if (failure == null)
                    failure = new IllegalStateException("Release capture workspace", error);
                else
                    failure.addSuppressed(error);
            }
        }
        workspaces.clear();
        if (failure != null)
            throw failure;
    }
}
