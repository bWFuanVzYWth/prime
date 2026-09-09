// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.dlss;

import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import java.util.Objects;

/** DLSS RR-specific native quality and long-cycle temporal sampling policy. */
public final class DlssRrProfile {
    private DlssRrProfile() {
    }

    /** Native NVSDK_NGX_PerfQuality_Value numeric value. */
    public static int ngxPerfQualityValue(ReconstructionQualityMode quality) {
        Objects.requireNonNull(quality, "quality");
        return switch (quality) {
            case NATIVE_AA -> 5;
            case QUALITY -> 2;
            case BALANCED -> 1;
            case PERFORMANCE -> 0;
            case ULTRA_PERFORMANCE -> 3;
        };
    }

    /** RR uses a long Halton cycle to avoid a visible short-period sub-pixel pattern. */
    public static int jitterPhaseCount(ReconstructionQualityMode quality) {
        return Math.max(
                64,
                quality.jitterPhaseCount());
    }

    public static int jitterPhase(ReconstructionQualityMode quality, int frameIndex) {
        return Math.floorMod(frameIndex, jitterPhaseCount(quality)) + 1;
    }

    public static SubpixelJitter jitter(
            ReconstructionQualityMode quality, int frameIndex) {
        int phase = jitterPhase(quality, frameIndex);
        return SubpixelJitter.halton(phase);
    }
}
