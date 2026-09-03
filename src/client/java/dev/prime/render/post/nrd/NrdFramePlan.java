package dev.prime.render.post.nrd;

import dev.prime.render.FrameCamera;
import dev.prime.render.FrameTime;
import dev.prime.render.SunDirection;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.TemporalReconstructionState;
import java.util.Objects;

/**
 * Immutable history and input-preparation semantics decided before GPU command recording.
 *
 * <p>The plan contains no native instance, Vulkan handle or mutable backend token.
 */
public record NrdFramePlan(
        FrameCamera camera,
        FrameCamera historyCamera,
        SubpixelJitter jitter,
        SubpixelJitter historyJitter,
        int frameIndex,
        boolean restart,
        float deltaMilliseconds,
        SunDirection sunDirection) {
    public NrdFramePlan {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(historyCamera, "historyCamera");
        Objects.requireNonNull(jitter, "jitter");
        Objects.requireNonNull(historyJitter, "historyJitter");
        Objects.requireNonNull(sunDirection, "sunDirection");
        if (!Float.isFinite(deltaMilliseconds)
                || deltaMilliseconds < 0.0F
                || deltaMilliseconds
                        > FrameTime.MAXIMUM_DELTA_MILLISECONDS) {
            throw new IllegalArgumentException(
                    "NRD planned temporal values must be finite and non-negative where required");
        }
        if (frameIndex < 0) {
            throw new IllegalArgumentException(
                    "NRD planned frame index must be non-negative");
        }
    }

    public static NrdFramePlan from(
            TemporalReconstructionState.Plan temporal,
            SubpixelJitter jitter,
            ReconstructionQualityMode quality,
            SunDirection sunDirection) {
        Objects.requireNonNull(temporal, "temporal");
        Objects.requireNonNull(quality, "quality");
        SubpixelJitter historyJitter = temporal.restart()
                ? jitter
                : quality.jitter(Math.subtractExact(temporal.frameIndex(), 1));
        return new NrdFramePlan(
                temporal.camera(),
                temporal.historyCamera(),
                jitter,
                historyJitter,
                temporal.frameIndex(),
                temporal.restart(),
                temporal.deltaMilliseconds(),
                sunDirection);
    }
}
