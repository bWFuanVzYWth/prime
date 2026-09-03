package dev.prime.config;

import dev.prime.binding.streamline.ReflexMode;
import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.HdrOutput;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.MaximumBounceSettings;
import dev.prime.render.MinimumBounceSettings;
import dev.prime.render.SpecularBounceSettings;
import dev.prime.render.SurfaceDetailMode;
import dev.prime.render.TransparentNeeMode;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainWorkerSettings;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import java.util.Objects;

/** Client-thread-owned state transferred to and from the properties codec. */
final class PrimeConfigData {
    boolean pathTracingEnabled;
    int additionalSpecularBounces;
    int minimumBounces;
    int maximumBounces;
    int terrainWorkerPercentage;
    SurfaceDetailMode surfaceDetailMode;
    int voxelTextureSurfaceStrengthSteps;
    PostProcessingMode postProcessingMode;
    ReconstructionQualityMode reconstructionQuality;
    AstronomySettings astronomy;
    LightingSettings.Snapshot lighting;
    DisplaySettings.Snapshot display;
    MaterialSettings.Snapshot material;
    boolean hdrEnabled;
    int referenceWhiteNits;
    ReflexMode reflexMode;
    boolean dlssFrameGenerationEnabled;
    int dlssFrameGenerationMultiplier;
    boolean dlssFrameGenerationUiRecomposition;

    PrimeConfigData(
            boolean pathTracingEnabled,
            int additionalSpecularBounces,
            int minimumBounces,
            int maximumBounces,
            int terrainWorkerPercentage,
            SurfaceDetailMode surfaceDetailMode,
            int voxelTextureSurfaceStrengthSteps,
            PostProcessingMode postProcessingMode,
            ReconstructionQualityMode reconstructionQuality,
            AstronomySettings astronomy,
            LightingSettings.Snapshot lighting,
            DisplaySettings.Snapshot display,
            MaterialSettings.Snapshot material,
            boolean hdrEnabled,
            int referenceWhiteNits,
            ReflexMode reflexMode,
            boolean dlssFrameGenerationEnabled,
            int dlssFrameGenerationMultiplier,
            boolean dlssFrameGenerationUiRecomposition) {
        this.pathTracingEnabled = pathTracingEnabled;
        this.additionalSpecularBounces =
                SpecularBounceSettings.validateCount(additionalSpecularBounces);
        this.minimumBounces = MinimumBounceSettings.validateCount(minimumBounces);
        this.maximumBounces = MaximumBounceSettings.validateCount(maximumBounces);
        this.terrainWorkerPercentage =
                TerrainWorkerSettings.validatePercentage(terrainWorkerPercentage);
        this.surfaceDetailMode = Objects.requireNonNull(surfaceDetailMode, "surfaceDetailMode");
        this.voxelTextureSurfaceStrengthSteps = voxelTextureSurfaceStrengthSteps;
        VoxelSurfaceSettings.maximumHeight(voxelTextureSurfaceStrengthSteps);
        this.postProcessingMode = Objects.requireNonNull(postProcessingMode, "postProcessingMode");
        if (postProcessingMode == PostProcessingMode.DISABLED) {
            throw new IllegalArgumentException("Raw output is a non-persistent session diagnostic");
        }
        this.reconstructionQuality =
                Objects.requireNonNull(reconstructionQuality, "reconstructionQuality");
        this.astronomy = Objects.requireNonNull(astronomy, "astronomy");
        this.lighting = Objects.requireNonNull(lighting, "lighting");
        this.display = Objects.requireNonNull(display, "display");
        this.material = Objects.requireNonNull(material, "material");
        this.hdrEnabled = hdrEnabled;
        this.referenceWhiteNits = HdrOutput.validateReferenceWhiteNits(referenceWhiteNits);
        this.reflexMode = Objects.requireNonNull(reflexMode, "reflexMode");
        this.dlssFrameGenerationEnabled = dlssFrameGenerationEnabled;
        if (dlssFrameGenerationMultiplier < 2) {
            throw new IllegalArgumentException("DLSS frame generation multiplier must be at least 2");
        }
        this.dlssFrameGenerationMultiplier = dlssFrameGenerationMultiplier;
        this.dlssFrameGenerationUiRecomposition = dlssFrameGenerationUiRecomposition;
    }

    static PrimeConfigData defaults() {
        return new PrimeConfigData(
                true,
                SpecularBounceSettings.DEFAULT_COUNT,
                MinimumBounceSettings.DEFAULT_COUNT,
                MaximumBounceSettings.DEFAULT_COUNT,
                TerrainWorkerSettings.DEFAULT_PERCENTAGE,
                SurfaceDetailMode.DEFAULT,
                VoxelSurfaceSettings.DEFAULT_STEPS,
                PostProcessingMode.DEFAULT,
                ReconstructionQualityMode.DEFAULT,
                AstronomySettings.defaults(),
                new LightingSettings.Snapshot(
                        LightingSettings.DEFAULT_SUN_QUARTER_STEPS,
                        LightingSettings.DEFAULT_STAR_QUARTER_STEPS,
                        LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS,
                        TransparentNeeMode.DEFAULT,
                        0L),
                new DisplaySettings.Snapshot(
                        DisplaySettings.DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS,
                        DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS),
                new MaterialSettings.Snapshot(
                        MaterialSettings.DEFAULT_ROUGHNESS_STEPS,
                        MaterialSettings.DEFAULT_SEAMLESS_GLASS,
                        MaterialSettings.DEFAULT_AIR_GAP,
                        MaterialSettings.DEFAULT_VANILLA_PBR_PRESETS,
                        0L),
                false,
                HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS,
                ReflexMode.OFF,
                false,
                2,
                true);
    }
}
