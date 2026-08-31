package dev.prime.render;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.TransparentGuideMode;
import java.util.Objects;

/** Device-address-free semantic input captured once for an interactive frame. */
public record RealtimeFrameInput(
        FrameCamera camera,
        long frameTimeNanos,
        long sceneRevision,
        long residentSceneRevision,
        long textureRevision,
        int width,
        int height,
        int displayWidth,
        int displayHeight,
        AstronomyState astronomy,
        boolean cameraInWater,
        PostProcessingMode postProcessingMode,
        ReconstructionQualityMode quality,
        TransparentGuideMode transparentGuideMode,
        int additionalSpecularBounces,
        int minimumBounces,
        int maximumBounces,
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        boolean shInput,
        DisplaySettings.Snapshot display,
        boolean forceReset) {
    public RealtimeFrameInput(
            FrameCamera camera,
            long frameTimeNanos,
            long sceneRevision,
            long residentSceneRevision,
            long textureRevision,
            int width,
            int height,
            int displayWidth,
            int displayHeight,
            AstronomyState astronomy,
            boolean cameraInWater,
            PostProcessingMode postProcessingMode,
            ReconstructionQualityMode quality,
            TransparentGuideMode transparentGuideMode,
            int maximumBounces,
            LightingSettings.Snapshot lighting,
            MaterialSettings.Snapshot material,
            boolean shInput,
            DisplaySettings.Snapshot display,
            boolean forceReset) {
        this(
                camera, frameTimeNanos, sceneRevision, residentSceneRevision,
                textureRevision, width, height, displayWidth, displayHeight, astronomy,
                cameraInWater, postProcessingMode, quality, transparentGuideMode,
                SpecularBounceSettings.DEFAULT_COUNT, MinimumBounceSettings.DEFAULT_COUNT,
                maximumBounces, lighting, material,
                shInput, display, forceReset);
    }

    public RealtimeFrameInput(
            FrameCamera camera,
            long frameTimeNanos,
            long sceneRevision,
            long residentSceneRevision,
            long textureRevision,
            int width,
            int height,
            int displayWidth,
            int displayHeight,
            AstronomyState astronomy,
            boolean cameraInWater,
            PostProcessingMode postProcessingMode,
            ReconstructionQualityMode quality,
            TransparentGuideMode transparentGuideMode,
            int maximumBounces,
            int additionalSpecularBounces,
            LightingSettings.Snapshot lighting,
            MaterialSettings.Snapshot material,
            boolean shInput,
            DisplaySettings.Snapshot display,
            boolean forceReset) {
        this(
                camera, frameTimeNanos, sceneRevision, residentSceneRevision,
                textureRevision, width, height, displayWidth, displayHeight, astronomy,
                cameraInWater, postProcessingMode, quality, transparentGuideMode,
                additionalSpecularBounces, MinimumBounceSettings.DEFAULT_COUNT,
                maximumBounces, lighting, material, shInput, display, forceReset);
    }

    public RealtimeFrameInput {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(astronomy, "astronomy");
        Objects.requireNonNull(postProcessingMode, "postProcessingMode");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(transparentGuideMode, "transparentGuideMode");
        IntegratorSettings.packPathControl(
                maximumBounces,
                0,
                astronomy.settings(),
                cameraInWater,
                transparentGuideMode);
        SpecularBounceSettings.validateCount(additionalSpecularBounces);
        MinimumBounceSettings.validateCount(minimumBounces);
        MaximumBounceSettings.validateCount(maximumBounces);
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(display, "display");
        if (width <= 0 || height <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException(
                    "Realtime render and display extents must be positive");
        }
        if (residentSceneRevision < 0L) {
            throw new IllegalArgumentException(
                    "Realtime resident scene revision must be non-negative");
        }
    }

    public RealtimeSampleState.Input sampleStateInput() {
        return new RealtimeSampleState.Input(
                this.camera, this.sceneRevision, this.forceReset);
    }

    public ReconstructionFrameParameters reconstructionInput(boolean forceRestart) {
        return new ReconstructionFrameParameters(
                this.camera,
                this.frameTimeNanos,
                this.sceneRevision,
                this.textureRevision,
                forceRestart,
                this.sunDirection(),
                this.lighting,
                this.display);
    }

    void requireReconstructionInput(
            ReconstructionFrameParameters parameters, boolean forceRestart) {
        Objects.requireNonNull(parameters, "parameters");
        if (!this.camera.equals(parameters.camera())
                || this.frameTimeNanos != parameters.frameTimeNanos()
                || this.sceneRevision != parameters.sceneRevision()
                || this.textureRevision != parameters.textureRevision()
                || forceRestart != parameters.forceRestart()
                || !this.sunDirection().equals(parameters.sunDirection())
                || !this.lighting.equals(parameters.lighting())
                || !this.display.equals(parameters.display())) {
            throw new IllegalStateException(
                    "Reconstruction parameters do not match the captured frame input");
        }
    }

    IntegratorFrameInput integratorInput(
            int sampleIndex,
            int sampleEpoch,
            int jitterPhase,
            RayConeParameters rayCone,
            boolean historyValid) {
        return new IntegratorFrameInput(
                this.camera,
                this.width,
                this.height,
                this.astronomy,
                rayCone,
                this.additionalSpecularBounces,
                this.minimumBounces,
                this.maximumBounces,
                sampleIndex,
                sampleEpoch,
                jitterPhase,
                this.cameraInWater,
                this.postProcessingMode,
                this.transparentGuideMode,
                this.lighting,
                this.material,
                this.shInput,
                historyValid);
    }

    public SunDirection sunDirection() {
        return this.astronomy.sunDirection();
    }
}
