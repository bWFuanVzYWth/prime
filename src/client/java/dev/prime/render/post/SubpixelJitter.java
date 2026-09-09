// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.post;

/** Centered source-pixel sample displacement shared by every reconstruction backend. */
public record SubpixelJitter(float x, float y) {
    public SubpixelJitter {
        if (!Float.isFinite(x)
                || !Float.isFinite(y)
                || Math.abs(x) > 0.5F
                || Math.abs(y) > 0.5F) {
            throw new IllegalArgumentException(
                    "Subpixel jitter must be finite and inside one source pixel");
        }
    }

    /** FSR consumes projection displacement, the inverse of Prime's sample displacement. */
    public SubpixelJitter forFsrDispatch() {
        return new SubpixelJitter(-this.x, -this.y);
    }

    public static SubpixelJitter halton(int phase) {
        if (phase <= 0) {
            throw new IllegalArgumentException("Halton phase must be positive");
        }
        return new SubpixelJitter(
                halton(phase, 2) - 0.5F,
                halton(phase, 3) - 0.5F);
    }

    private static float halton(int index, int base) {
        float result = 0.0F;
        float fraction = 1.0F;
        int value = index;
        while (value > 0) {
            fraction /= base;
            result += fraction * (value % base);
            value /= base;
        }
        return result;
    }
}
