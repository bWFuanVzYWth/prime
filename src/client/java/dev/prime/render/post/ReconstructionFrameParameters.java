package dev.prime.render.post;

import dev.prime.render.DisplaySettings;
import dev.prime.render.FrameCamera;
import dev.prime.render.LightingSettings;
import dev.prime.render.SunDirection;
import java.util.Objects;

/** Backend-neutral semantic input for one temporal reconstruction transition. */
public record ReconstructionFrameParameters(
        FrameCamera camera,
        long frameTimeNanos,
        long sceneRevision,
        boolean forceRestart,
        SunDirection sunDirection,
        LightingSettings.Snapshot lighting,
        DisplaySettings.Snapshot display) {
    public ReconstructionFrameParameters {
        camera = Objects.requireNonNull(camera, "camera");
        sunDirection = Objects.requireNonNull(sunDirection, "sunDirection");
        lighting = Objects.requireNonNull(lighting, "lighting");
        display = Objects.requireNonNull(display, "display");
    }

    public float sunRadianceMultiplier() {
        return this.lighting.sunMultiplier();
    }
}
