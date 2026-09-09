// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

/** Pure conversion from captured monotonic timestamps to backend frame time. */
public final class FrameTime {
    public static final float INITIAL_DELTA_MILLISECONDS = 1000.0F / 60.0F;
    public static final float MAXIMUM_DELTA_MILLISECONDS = 1000.0F;

    private FrameTime() {
    }

    public static float deltaMilliseconds(
            boolean initialized, long currentNanos, long previousNanos) {
        if (!initialized) {
            return INITIAL_DELTA_MILLISECONDS;
        }
        long elapsedNanos;
        try {
            elapsedNanos = Math.subtractExact(
                    currentNanos, previousNanos);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Captured frame time interval overflowed", exception);
        }
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException(
                    "Captured frame time must not move backwards");
        }
        return Math.min(
                elapsedNanos * 1.0e-6F,
                MAXIMUM_DELTA_MILLISECONDS);
    }
}
