package dev.prime.render;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.TransparentGuideMode;
import java.util.Objects;

/**
 * Complete device-address-free semantic input of one path integrator dispatch.
 *
 * <p>GPU residency is bound only by the device executor. The same value can therefore be encoded,
 * persisted and rebound without allocator handles becoming part of render identity.
 */
public record IntegratorFrameInput(
        FrameCamera camera,
        int width,
        int height,
        AstronomyState astronomy,
        RayConeParameters rayCone,
        int additionalSpecularBounces,
        int minimumBounces,
        int maximumBounces,
        int sampleIndex,
        int sampleEpoch,
        int jitterPhase,
        boolean cameraInWater,
        PostProcessingMode postProcessingMode,
        TransparentGuideMode transparentGuideMode,
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        boolean shInput,
        boolean historyValid) {
    private static final int ZSOBOL_MAXIMUM_EXTENT = 1 << 18;

    public IntegratorFrameInput(
            FrameCamera camera,
            int width,
            int height,
            AstronomyState astronomy,
            RayConeParameters rayCone,
            int maximumBounces,
            int sampleIndex,
            int sampleEpoch,
            int jitterPhase,
            boolean cameraInWater,
            PostProcessingMode postProcessingMode,
            TransparentGuideMode transparentGuideMode,
            LightingSettings.Snapshot lighting,
            MaterialSettings.Snapshot material,
            boolean shInput) {
        this(
                camera, width, height, astronomy, rayCone,
                SpecularBounceSettings.DEFAULT_COUNT, MinimumBounceSettings.DEFAULT_COUNT,
                maximumBounces,
                sampleIndex, sampleEpoch, jitterPhase,
                cameraInWater, postProcessingMode, transparentGuideMode, lighting, material,
                shInput, sampleIndex != 0);
    }

    public IntegratorFrameInput(
            FrameCamera camera,
            int width,
            int height,
            AstronomyState astronomy,
            RayConeParameters rayCone,
            int maximumBounces,
            int additionalSpecularBounces,
            int sampleIndex,
            int sampleEpoch,
            int jitterPhase,
            boolean cameraInWater,
            PostProcessingMode postProcessingMode,
            TransparentGuideMode transparentGuideMode,
            LightingSettings.Snapshot lighting,
            MaterialSettings.Snapshot material,
            boolean shInput) {
        this(
                camera, width, height, astronomy, rayCone,
                additionalSpecularBounces, MinimumBounceSettings.DEFAULT_COUNT,
                maximumBounces,
                sampleIndex, sampleEpoch, jitterPhase, cameraInWater,
                postProcessingMode, transparentGuideMode, lighting, material, shInput);
    }

    public IntegratorFrameInput(
            FrameCamera camera,
            int width,
            int height,
            AstronomyState astronomy,
            RayConeParameters rayCone,
            int additionalSpecularBounces,
            int minimumBounces,
            int maximumBounces,
            int sampleIndex,
            int sampleEpoch,
            int jitterPhase,
            boolean cameraInWater,
            PostProcessingMode postProcessingMode,
            TransparentGuideMode transparentGuideMode,
            LightingSettings.Snapshot lighting,
            MaterialSettings.Snapshot material,
            boolean shInput) {
        this(
                camera,
                width,
                height,
                astronomy,
                rayCone,
                additionalSpecularBounces,
                minimumBounces,
                maximumBounces,
                sampleIndex,
                sampleEpoch,
                jitterPhase,
                cameraInWater,
                postProcessingMode,
                transparentGuideMode,
                lighting,
                material,
                shInput,
                sampleIndex != 0);
    }

    public IntegratorFrameInput {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(astronomy, "astronomy");
        Objects.requireNonNull(rayCone, "rayCone");
        Objects.requireNonNull(postProcessingMode, "postProcessingMode");
        Objects.requireNonNull(transparentGuideMode, "transparentGuideMode");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(material, "material");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Integrator extent must be positive");
        }
        // pbrt's 52-column Sobol matrices leave 36 Morton bits after Prime's 16-bit
        // progressive sample index, hence at most 18 coordinate bits per axis.
        if (width > ZSOBOL_MAXIMUM_EXTENT || height > ZSOBOL_MAXIMUM_EXTENT) {
            throw new IllegalArgumentException(
                    "Integrator extent exceeds the Z-Sobol sequence domain");
        }
        if (sampleIndex < 0 || sampleIndex >= 1 << 16) {
            throw new IllegalArgumentException(
                    "Sample index must fit the Sobol sequence");
        }
        if (!camera.isFinite()) {
            throw new IllegalArgumentException(
                    "Integrator camera must be finite");
        }
        IntegratorSettings.packSampleControl(
                sampleIndex,
                astronomy.settings(),
                material.seamlessGlass(),
                material.airGap(),
                material.vanillaPbrPresets(),
                lighting.transparentNeeMode());
        IntegratorSettings.packSampleEpoch(sampleEpoch, historyValid);
        IntegratorSettings.packPathControl(
                maximumBounces,
                jitterPhase,
                astronomy.settings(),
                cameraInWater,
                transparentGuideMode);
        SpecularBounceSettings.validateCount(additionalSpecularBounces);
        MinimumBounceSettings.validateCount(minimumBounces);
        MaximumBounceSettings.validateCount(maximumBounces);
        IntegratorSettings.packMaterialLightingControl(
                lighting.sunQuarterSteps(),
                lighting.starQuarterSteps(),
                lighting.blockLightQuarterSteps(),
                material.roughnessSteps(),
                shInput);
    }

    public SunDirection sunDirection() {
        return this.astronomy.sunDirection();
    }
}
