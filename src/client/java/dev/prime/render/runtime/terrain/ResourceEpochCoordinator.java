// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime.terrain;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates access to reload-owned external resources without blocking their owner thread.
 *
 * <p>The atomic current generation is the only shared mutable state. Callers hold short leases
 * while reading Minecraft-owned resources; reload retires that generation and asynchronously
 * waits for its existing readers. No monitor, lock order, or polling is involved.
 */
final class ResourceEpochCoordinator implements AutoCloseable {
    private static final int RETIRED = Integer.MIN_VALUE;
    private static final int READER_MASK = Integer.MAX_VALUE;
    private static final Generation CLOSED = Generation.sentinel();

    private final AtomicReference<Generation> current =
            new AtomicReference<>(new Generation(0L));

    Lease tryAcquire() {
        Generation generation = this.current.get();
        if (generation == null || generation == CLOSED || !generation.tryAcquire()) {
            return null;
        }
        if (this.current.get() != generation) {
            generation.release();
            return null;
        }
        return new Lease(this, generation);
    }

    Lease tryAcquire(Epoch epoch) {
        Objects.requireNonNull(epoch, "epoch");
        if (epoch.owner != this) {
            throw new IllegalArgumentException("Resource epoch belongs to another coordinator");
        }
        Generation generation = epoch.generation;
        if (this.current.get() != generation || !generation.tryAcquire()) {
            return null;
        }
        if (this.current.get() != generation) {
            generation.release();
            return null;
        }
        return new Lease(this, generation);
    }

    Reload pause() {
        while (true) {
            Generation generation = this.current.get();
            if (generation == CLOSED) {
                throw new IllegalStateException("Resource epoch coordinator is closed");
            }
            if (generation == null) {
                throw new IllegalStateException("A resource reload is already in progress");
            }
            if (this.current.compareAndSet(generation, null)) {
                generation.retire();
                return new Reload(this, generation);
            }
        }
    }

    void finish(Reload reload) {
        this.resume(reload, Resolution.FINISHED);
    }

    void abort(Reload reload) {
        this.resume(reload, Resolution.ABORTED);
    }

    @Override
    public void close() {
        Generation generation = this.current.getAndSet(CLOSED);
        if (generation != null && generation != CLOSED) {
            generation.retire();
        }
    }

    private void resume(Reload reload, Resolution resolution) {
        Objects.requireNonNull(reload, "reload");
        if (reload.owner != this) {
            throw new IllegalArgumentException("Resource reload belongs to another coordinator");
        }
        Resolution previous = reload.resolution.get();
        if (previous == resolution) {
            if (resolution == Resolution.ABORTED) {
                return;
            }
            throw new IllegalStateException("Resource reload was already finished");
        }
        if (previous != Resolution.PENDING
                || !reload.resolution.compareAndSet(Resolution.PENDING, resolution)) {
            previous = reload.resolution.get();
            if (previous == resolution) {
                if (resolution == Resolution.ABORTED) {
                    return;
                }
                throw new IllegalStateException("Resource reload was already finished");
            }
            throw new IllegalStateException(
                    "Resource reload was already resolved as " + previous.name().toLowerCase());
        }

        reload.generation.drained.thenRun(() -> this.installReplacement(reload));
    }

    private void installReplacement(Reload reload) {
        Generation replacement = new Generation(Math.incrementExact(reload.generation.id));
        if (!this.current.compareAndSet(null, replacement)) {
            Generation observed = this.current.get();
            if (observed == CLOSED) {
                return;
            }
            throw new IllegalStateException("Resolved resource reload crossed another generation");
        }
    }

    static final class Epoch {
        private final ResourceEpochCoordinator owner;
        private final Generation generation;

        private Epoch(ResourceEpochCoordinator owner, Generation generation) {
            this.owner = owner;
            this.generation = generation;
        }

        long id() {
            return this.generation.id;
        }
    }

    static final class Lease implements AutoCloseable {
        private final ResourceEpochCoordinator owner;
        private final Generation generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(ResourceEpochCoordinator owner, Generation generation) {
            this.owner = owner;
            this.generation = generation;
        }

        Epoch epoch() {
            return new Epoch(this.owner, this.generation);
        }

        @Override
        public void close() {
            if (!this.closed.compareAndSet(false, true)) {
                throw new IllegalStateException("Resource epoch lease is already closed");
            }
            this.generation.release();
        }
    }

    static final class Reload {
        private final ResourceEpochCoordinator owner;
        private final Generation generation;
        private final AtomicReference<Resolution> resolution =
                new AtomicReference<>(Resolution.PENDING);

        private Reload(ResourceEpochCoordinator owner, Generation generation) {
            this.owner = owner;
            this.generation = generation;
        }

        CompletableFuture<Void> ready() {
            return this.generation.drained.copy();
        }

    }

    private enum Resolution {
        PENDING,
        FINISHED,
        ABORTED
    }

    private static final class Generation {
        private final long id;
        private final AtomicInteger state = new AtomicInteger();
        private final CompletableFuture<Void> drained = new CompletableFuture<>();

        private Generation(long id) {
            this.id = id;
        }

        private static Generation sentinel() {
            Generation generation = new Generation(Long.MIN_VALUE);
            generation.state.set(RETIRED);
            generation.drained.complete(null);
            return generation;
        }

        private boolean tryAcquire() {
            while (true) {
                int observed = this.state.get();
                if ((observed & RETIRED) != 0) {
                    return false;
                }
                int readers = observed & READER_MASK;
                if (readers == READER_MASK) {
                    throw new IllegalStateException("Resource epoch reader count overflow");
                }
                if (this.state.compareAndSet(observed, observed + 1)) {
                    return true;
                }
            }
        }

        private void release() {
            while (true) {
                int observed = this.state.get();
                int readers = observed & READER_MASK;
                if (readers == 0) {
                    throw new IllegalStateException("Resource epoch reader count underflow");
                }
                int replacement = (observed & RETIRED) | (readers - 1);
                if (this.state.compareAndSet(observed, replacement)) {
                    if (replacement == RETIRED) {
                        this.drained.complete(null);
                    }
                    return;
                }
            }
        }

        private void retire() {
            while (true) {
                int observed = this.state.get();
                if ((observed & RETIRED) != 0) {
                    return;
                }
                int replacement = observed | RETIRED;
                if (this.state.compareAndSet(observed, replacement)) {
                    if ((replacement & READER_MASK) == 0) {
                        this.drained.complete(null);
                    }
                    return;
                }
            }
        }
    }
}
