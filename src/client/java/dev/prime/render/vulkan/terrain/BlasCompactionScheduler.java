package dev.prime.render.vulkan.terrain;

import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.PreparedBlas;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Render-thread-owned FIFO admission and lifetime accounting for BLAS compaction. */
final class BlasCompactionScheduler implements AutoCloseable {
    static final long TARGET_BUDGET_BYTES = 64L * 1024L * 1024L;
    private static final long FRAME_BUDGET_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_JOBS_PER_FRAME = 16;

    private final ArrayList<Job> jobs = new ArrayList<>();
    private final IdentityHashMap<PreparedBlas, Job> byBlas = new IdentityHashMap<>();
    private final TargetBudget targetBudget = new TargetBudget();
    private long reclaimedBytes;
    private long completedCount;
    private boolean closed;

    void register(GpuCluster cluster) {
        this.requireOpen();
        cluster.forEachBlas(this::register);
    }

    void unregister(GpuCluster cluster) {
        cluster.forEachBlas(this::unregister);
    }

    private void register(PreparedBlas blas) {
        if (!blas.compactionEnabled()) {
            return;
        }
        Job existing = this.byBlas.get(blas);
        if (existing != null) {
            existing.references = Math.incrementExact(existing.references);
            existing.cancelled = false;
            return;
        }
        PreparedBlas.CompactionState state = blas.compactionState();
        if (state == PreparedBlas.CompactionState.NOT_BENEFICIAL
                || state == PreparedBlas.CompactionState.COMPACTED
                || state == PreparedBlas.CompactionState.DESTROYED) {
            return;
        }
        Job job = new Job(blas);
        job.references = 1;
        this.jobs.add(job);
        this.byBlas.put(blas, job);
    }

    private void unregister(PreparedBlas blas) {
        Job job = this.byBlas.get(blas);
        if (job != null) {
            if (job.references <= 0) {
                throw new IllegalStateException(
                        "BLAS compaction reference count underflow");
            }
            job.references--;
            job.cancelled = job.references == 0;
        }
    }

    boolean hasReadyWork() {
        this.refresh();
        for (Job job : this.jobs) {
            if (!job.cancelled
                    && job.compaction == null
                    && job.blas.compactionState() == PreparedBlas.CompactionState.READY) {
                return true;
            }
        }
        return false;
    }

    Batch prepareBatch() {
        this.refresh();
        List<Job> admitted = admitReadyPrefix(
                this.jobs,
                this.targetBudget.reservedBytes(),
                job -> !job.cancelled
                        && job.compaction == null
                        && job.blas.compactionState() == PreparedBlas.CompactionState.READY,
                job -> job.blas.compactedSize());
        ArrayList<Selected> selected = new ArrayList<>();
        try {
            for (Job job : admitted) {
                selected.add(new Selected(job, job.blas.prepareCompaction()));
            }
            return new Batch(this, selected);
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            for (int index = selected.size() - 1; index >= 0; index--) {
                failure = ResourceCleanup.close(selected.get(index).compaction, failure);
            }
            throw failure;
        }
    }

    Snapshot snapshot() {
        this.requireOpen();
        int waiting = 0;
        int ready = 0;
        int retiring = 0;
        long waitingSourceBytes = 0L;
        long readySourceBytes = 0L;
        long inFlightSourceBytes = 0L;
        long knownReclaimableBytes = 0L;
        for (Job job : this.jobs) {
            PreparedBlas.CompactionState state = job.blas.compactionState();
            if (job.compaction != null) {
                retiring++;
                inFlightSourceBytes = Math.addExact(
                        inFlightSourceBytes, job.compaction.sourceSize());
                if (job.published || !job.cancelled) {
                    knownReclaimableBytes = Math.addExact(
                            knownReclaimableBytes, job.compaction.reclaimedBytes());
                }
            } else if (job.cancelled) {
                continue;
            } else if (state == PreparedBlas.CompactionState.READY) {
                ready++;
                readySourceBytes = Math.addExact(
                        readySourceBytes, job.blas.accelerationStructure().backingSize());
                knownReclaimableBytes = Math.addExact(
                        knownReclaimableBytes, job.blas.compactionReclaimedBytes());
            } else if (state != PreparedBlas.CompactionState.NOT_BENEFICIAL
                    && state != PreparedBlas.CompactionState.COMPACTED
                    && state != PreparedBlas.CompactionState.DESTROYED) {
                waiting++;
                waitingSourceBytes = Math.addExact(
                        waitingSourceBytes, job.blas.accelerationStructure().backingSize());
            }
        }
        return new Snapshot(
                waiting,
                ready,
                retiring,
                waitingSourceBytes,
                readySourceBytes,
                inFlightSourceBytes,
                knownReclaimableBytes,
                this.targetBudget.reservedBytes(),
                this.targetBudget.highWaterBytes(),
                this.reclaimedBytes,
                this.completedCount);
    }

    private void refresh() {
        this.requireOpen();
        for (int index = 0; index < this.jobs.size(); ) {
            Job job = this.jobs.get(index);
            if (job.compaction != null && job.compaction.retirementComplete()) {
                this.targetBudget.release(job.compaction.targetSize());
                if (job.published) {
                    this.reclaimedBytes = Math.addExact(
                            this.reclaimedBytes, job.compaction.reclaimedBytes());
                    this.completedCount = Math.incrementExact(this.completedCount);
                    job.cancelled = true;
                }
                job.compaction = null;
                job.published = false;
            }
            PreparedBlas.CompactionState state = job.blas.compactionState();
            if (!job.cancelled && state == PreparedBlas.CompactionState.QUERY_READY) {
                job.blas.resolveCompactionSize();
                state = job.blas.compactionState();
            }
            if (job.compaction == null
                    && (job.cancelled
                            || state == PreparedBlas.CompactionState.NOT_BENEFICIAL
                            || state == PreparedBlas.CompactionState.COMPACTED
                            || state == PreparedBlas.CompactionState.DESTROYED)) {
                this.byBlas.remove(job.blas);
                this.jobs.remove(index);
                continue;
            }
            index++;
        }
    }

    private void track(List<Selected> selected, boolean published) {
        for (Selected item : selected) {
            if (item.job.compaction != null) {
                throw new IllegalStateException("BLAS compaction job is already in flight");
            }
            item.job.compaction = item.compaction;
            item.job.published = published;
            this.targetBudget.reserve(item.compaction.targetSize());
        }
    }

    static <T> List<T> admitReadyPrefix(
            List<T> jobs,
            long reservedBytes,
            Predicate<T> ready,
            ToLongFunction<T> targetBytes) {
        if (reservedBytes < 0L) {
            throw new IllegalArgumentException("Reserved compaction bytes must not be negative");
        }
        long remaining = Math.max(0L, TARGET_BUDGET_BYTES - reservedBytes);
        long frameRemaining = FRAME_BUDGET_BYTES;
        ArrayList<T> admitted = new ArrayList<>();
        for (T job : jobs) {
            if (admitted.size() == MAX_JOBS_PER_FRAME) break;
            if (!ready.test(job)) {
                continue;
            }
            long bytes = targetBytes.applyAsLong(job);
            if (bytes <= 0L) {
                throw new IllegalArgumentException(
                        "Compaction target bytes must be positive");
            }
            if (bytes > remaining) {
                if (admitted.isEmpty()
                        && reservedBytes == 0L
                        && bytes > TARGET_BUDGET_BYTES) {
                    admitted.add(job);
                }
                break;
            }
            // A single indivisible BLAS may exceed the frame budget, but never bypass the
            // in-flight memory budget above or share that frame's compaction batch.
            if (bytes > frameRemaining && !admitted.isEmpty()) break;
            admitted.add(job);
            remaining -= bytes;
            frameRemaining -= bytes;
            if (frameRemaining <= 0L) break;
        }
        return List.copyOf(admitted);
    }

    @Override
    public void close() {
        this.closed = true;
        this.jobs.clear();
        this.byBlas.clear();
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("BLAS compaction scheduler is closed");
        }
    }

    record Snapshot(
            int waiting,
            int ready,
            int retiring,
            long waitingSourceBytes,
            long readySourceBytes,
            long inFlightSourceBytes,
            long knownReclaimableBytes,
            long reservedTargetBytes,
            long highWaterTargetBytes,
            long reclaimedBytes,
            long completedCount) {}

    static final class Batch implements AutoCloseable {
        private final BlasCompactionScheduler owner;
        private final List<Selected> selected;
        private final List<PreparedBlas.Compaction> compactions;
        private boolean tracked;

        private Batch(BlasCompactionScheduler owner, List<Selected> selected) {
            this.owner = owner;
            this.selected = List.copyOf(selected);
            this.compactions = selected.stream().map(Selected::compaction).toList();
        }

        boolean isEmpty() {
            return this.selected.isEmpty();
        }

        List<PreparedBlas.Compaction> compactions() {
            return this.compactions;
        }

        void commitPublished() {
            if (this.tracked) {
                throw new IllegalStateException("BLAS compaction batch was already committed");
            }
            this.owner.track(this.selected, true);
            this.tracked = true;
        }

        void abandonAfterSubmission() {
            if (this.tracked) {
                throw new IllegalStateException("BLAS compaction batch was already committed");
            }
            RuntimeException failure = null;
            for (PreparedBlas.Compaction compaction : this.compactions) {
                failure = ResourceCleanup.run(compaction::abandonAfterSubmission, failure);
            }
            this.owner.track(this.selected, false);
            this.tracked = true;
            ResourceCleanup.throwIfFailed(failure);
        }

        @Override
        public void close() {
            if (!this.tracked) {
                RuntimeException failure = null;
                for (int index = this.compactions.size() - 1; index >= 0; index--) {
                    failure = ResourceCleanup.close(this.compactions.get(index), failure);
                }
                this.tracked = true;
                ResourceCleanup.throwIfFailed(failure);
            }
        }
    }

    static final class TargetBudget {
        private long reservedBytes;
        private long highWaterBytes;

        void reserve(long bytes) {
            if (bytes <= 0L) {
                throw new IllegalArgumentException(
                        "Compaction target bytes must be positive");
            }
            this.reservedBytes = Math.addExact(this.reservedBytes, bytes);
            this.highWaterBytes = Math.max(this.highWaterBytes, this.reservedBytes);
        }

        void release(long bytes) {
            if (bytes <= 0L || bytes > this.reservedBytes) {
                throw new IllegalStateException(
                        "Released compaction bytes are not reserved");
            }
            this.reservedBytes = Math.subtractExact(this.reservedBytes, bytes);
        }

        long reservedBytes() {
            return this.reservedBytes;
        }

        long highWaterBytes() {
            return this.highWaterBytes;
        }
    }

    private static final class Job {
        private final PreparedBlas blas;
        private PreparedBlas.Compaction compaction;
        private boolean published;
        private boolean cancelled;
        private int references;

        private Job(PreparedBlas blas) {
            this.blas = blas;
        }
    }

    private record Selected(Job job, PreparedBlas.Compaction compaction) {}
}
