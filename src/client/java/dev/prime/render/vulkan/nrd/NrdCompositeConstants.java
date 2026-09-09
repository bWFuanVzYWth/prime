// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.nrd;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Exact std430 layout of {@code PrimeCompositePushConstants}. */
final class NrdCompositeConstants {
    static final int SIZE = 32;
    static final int OUTPUT_EXTENT = 0;
    static final int SUN_RADIANCE_MULTIPLIER = 8;
    static final int CURRENT_JITTER_PIXELS = 16;
    static final int EPIPOLE_NDC = 24;

    private NrdCompositeConstants() {
    }

    static void write(
            ByteBuffer target,
            int width,
            int height,
            float sunRadianceMultiplier,
            float cameraJitterX,
            float cameraJitterY,
            float epipoleX,
            float epipoleY) {
        Objects.requireNonNull(target, "target");
        if (target.capacity() < SIZE) {
            throw new IllegalArgumentException(
                    "NRD composite push-constant buffer is too small");
        }
        target.putInt(OUTPUT_EXTENT, width);
        target.putInt(OUTPUT_EXTENT + Integer.BYTES, height);
        target.putFloat(SUN_RADIANCE_MULTIPLIER, sunRadianceMultiplier);
        target.putInt(12, 0);
        target.putFloat(CURRENT_JITTER_PIXELS, cameraJitterX);
        target.putFloat(CURRENT_JITTER_PIXELS + Float.BYTES, cameraJitterY);
        target.putFloat(EPIPOLE_NDC, epipoleX);
        target.putFloat(EPIPOLE_NDC + Float.BYTES, epipoleY);
    }
}
