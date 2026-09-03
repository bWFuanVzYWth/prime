package dev.prime.render.post;

import dev.prime.render.DisplaySettings;
import dev.prime.render.FrameCamera;
import dev.prime.render.FrameTime;
import dev.prime.render.SunDirection;
import java.util.Objects;

/** Backend-neutral semantic input fixed by the interactive frame timeline. */
public record ReconstructionFrameParameters(
        FrameCamera camera,
        FrameCamera historyCamera,
        int frameIndex,
        SubpixelJitter jitter,
        boolean reset,
        float deltaMilliseconds,
        SunDirection sunDirection,
        float sunRadianceMultiplier,
        DisplaySettings.Snapshot display) {
    public ReconstructionFrameParameters {
        camera = Objects.requireNonNull(camera, "camera");
        historyCamera = Objects.requireNonNull(historyCamera, "historyCamera");
        jitter = Objects.requireNonNull(jitter, "jitter");
        sunDirection = Objects.requireNonNull(sunDirection, "sunDirection");
        display = Objects.requireNonNull(display, "display");
        if (frameIndex < 0
                || !Float.isFinite(deltaMilliseconds)
                || deltaMilliseconds < 0.0F
                || deltaMilliseconds > FrameTime.MAXIMUM_DELTA_MILLISECONDS
                || !Float.isFinite(sunRadianceMultiplier)
                || sunRadianceMultiplier < 0.0F) {
            throw new IllegalArgumentException("Invalid reconstruction temporal frame");
        }
    }
}
