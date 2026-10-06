package dev.primept;

/** SDK-presented frames per monotonic second; rendering cadence remains a separate metric. */
public final class PresentationFrameRate {
    private static final long WINDOW_NS = 1_000_000_000L;
    private static final long STALE_NS = 1_500_000_000L;
    private boolean observed, measuring;
    private long epoch, sample, total, observedAt, progressedAt, baselineAt, baselineTotal;
    private int framesPerSecond = -1;

    public int framesPerSecond() {
        return framesPerSecond;
    }

    public void clear() {
        observed = measuring = false;
        framesPerSecond = -1;
    }

    /** A busy CPU snapshot is not a new SDK sample; let its previous rate expire normally. */
    public void advance(long now) {
        if (observed && (now < observedAt || now - progressedAt > STALE_NS)) {
            measuring = false;
            framesPerSecond = -1;
        }
    }

    public void observe(long now, long currentEpoch, long presented, long sampleId, boolean active,
                        boolean valid) {
        if (!active || !valid || currentEpoch == 0) {
            clear();
            return;
        }
        boolean changedEpoch = !observed || epoch != currentEpoch;
        if (!changedEpoch && (Long.compareUnsigned(presented, total) < 0 ||
                              Long.compareUnsigned(sampleId, sample) < 0 ||
                              (sampleId == sample && presented != total))) {
            clear();
            return;
        }
        boolean progressed = changedEpoch || sampleId != sample;
        if (changedEpoch || now < observedAt || !progressed && now - progressedAt > STALE_NS) {
            measuring = false;
            framesPerSecond = -1;
        }
        observed = true;
        epoch = currentEpoch;
        sample = sampleId;
        total = presented;
        observedAt = now;
        if (!progressed)
            return;
        progressedAt = now;
        if (!measuring) {
            measuring = true;
            baselineAt = now;
            baselineTotal = presented;
            return;
        }
        long elapsed = now - baselineAt;
        if (elapsed < WINDOW_NS)
            return;
        long count = presented - baselineTotal;
        // More than 2^63 actual presentations in one window cannot form a usable rate.
        if (count < 0) {
            clear();
            return;
        }
        framesPerSecond =
                (int)Math.min(Integer.MAX_VALUE, Math.round(count * (double)WINDOW_NS / elapsed));
        baselineAt = now;
        baselineTotal = presented;
    }
}
