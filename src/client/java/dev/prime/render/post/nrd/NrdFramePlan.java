package dev.prime.render.post.nrd;

import dev.prime.render.FrameCamera;
import dev.prime.render.FrameTime;
import dev.prime.render.SunDirection;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
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
            ReconstructionFrameParameters frame,
            ReconstructionQualityMode quality) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(quality, "quality");
        SubpixelJitter historyJitter = frame.reset()
                ? frame.jitter()
                : quality.jitter(Math.subtractExact(frame.frameIndex(), 1));
        return new NrdFramePlan(
                frame.camera(),
                frame.historyCamera(),
                frame.jitter(),
                historyJitter,
                frame.frameIndex(),
                frame.reset(),
                frame.deltaMilliseconds(),
                frame.sunDirection());
    }
}
