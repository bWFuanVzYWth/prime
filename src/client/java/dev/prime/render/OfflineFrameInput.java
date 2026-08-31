package dev.prime.render;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.TransparentGuideMode;
import java.util.Objects;

/** Complete device-address-free semantic input captured for one offline sample. */
public record OfflineFrameInput(
        FrameCamera camera,
        int width,
        int height,
        long sceneRevision,
        long textureRevision,
        AstronomyState astronomy,
        boolean cameraInWater,
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        int maximumBounces,
        long sampleCount,
        DisplaySettings.Snapshot display) {
    public OfflineFrameInput(
            FrameCamera camera,
            int width,
            int height,
            long sceneRevision,
            long textureRevision,
            AstronomyState astronomy,
            boolean cameraInWater,
            LightingSettings.Snapshot lighting,
            MaterialSettings.Snapshot material,
            long sampleCount,
            DisplaySettings.Snapshot display) {
        this(
                camera, width, height, sceneRevision, textureRevision, astronomy,
                cameraInWater, lighting, material, MaximumBounceSettings.DEFAULT_COUNT,
                sampleCount, display);
    }

    public OfflineFrameInput {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(astronomy, "astronomy");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(display, "display");
        MaximumBounceSettings.validateCount(maximumBounces);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Offline extent must be positive");
        }
        if (sampleCount < 0L) {
            throw new IllegalArgumentException(
                    "Offline sample count must be non-negative");
        }
        if (sceneRevision < 0L || textureRevision < 0L) {
            throw new IllegalArgumentException(
                    "Offline scene and texture revisions must be non-negative");
        }
    }

    public OfflineFramePlan plan() {
        int sampleIndex = (int) (this.sampleCount & 0xffffL);
        int sampleEpoch = (int) (this.sampleCount >>> 16);
        return new OfflineFramePlan(
                this,
                new IntegratorFrameInput(
                        this.camera,
                        this.width,
                        this.height,
                        this.astronomy,
                        RayConeParameters.fromProjection(
                                this.camera.projection().m00(),
                                this.camera.projection().m11(),
                                this.width,
                                this.height,
                                0.0F),
                        this.maximumBounces,
                        sampleIndex,
                        sampleEpoch,
                        0,
                        this.cameraInWater,
                        PostProcessingMode.DISABLED,
                        TransparentGuideMode.DISABLED,
                        this.lighting,
                        this.material,
                        false));
    }

    public SunDirection sunDirection() {
        return this.astronomy.sunDirection();
    }

}
