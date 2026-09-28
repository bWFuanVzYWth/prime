package dev.primept;

/** CPU clock domains: hook cadence includes the previous hook, not the current one. */
public record
        FrameTimings(long interval, long previousHook, long extraction, long beforePT, long hook) {
    public static final long WARNING_NANOS = 50_000_000L;

    public long currentWork() {
        return extraction + beforePT + hook;
    }
    /** Unmeasured host work/waits between hooks; unavailable for the first observation. */
    public long outside() {
        return interval == 0 ? -1 : Math.max(0, interval - previousHook - extraction - beforePT);
    }
    public boolean slow() {
        return currentWork() >= WARNING_NANOS || interval >= WARNING_NANOS;
    }
}
