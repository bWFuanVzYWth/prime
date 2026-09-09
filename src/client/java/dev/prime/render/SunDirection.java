// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

/**
 * Unit world-space direction from the camera toward the apparent sun.
 */
public record SunDirection(float x, float y, float z) {
    public SunDirection {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("Sun direction must be finite");
        }
        float lengthSquared = x * x + y * y + z * z;
        if (!Float.isFinite(lengthSquared)
                || Math.abs(lengthSquared - 1.0F) > 1.0e-4F) {
            throw new IllegalArgumentException(
                    "Sun direction must have unit length");
        }
    }

}
