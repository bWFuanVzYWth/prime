package dev.prime.config;

import dev.prime.binding.streamline.ReflexMode;
import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.HdrOutput;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.BounceSettings;
import dev.prime.render.SurfaceDetailMode;
import dev.prime.render.TransparentNeeMode;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainWorkerSettings;
import dev.prime.render.terrain.VoxelSurfaceSettings;

/** Client-thread-owned state transferred to and from the properties codec. */
final class PrimeConfigData {
    boolean pathTracingEnabled = true;
    int additionalSpecularBounces = BounceSettings.DEFAULT_COUNT;
    int minimumBounces = BounceSettings.DEFAULT_FIXED_COUNT;
    int maximumBounces = BounceSettings.DEFAULT_COUNT;
    int terrainWorkerPercentage = TerrainWorkerSettings.DEFAULT_PERCENTAGE;
    SurfaceDetailMode surfaceDetailMode = SurfaceDetailMode.DEFAULT;
    int voxelTextureSurfaceStrengthSteps = VoxelSurfaceSettings.DEFAULT_STEPS;
    PostProcessingMode postProcessingMode = PostProcessingMode.DEFAULT;
    ReconstructionQualityMode reconstructionQuality = ReconstructionQualityMode.DEFAULT;
    AstronomySettings astronomy = AstronomySettings.defaults();
    LightingSettings.Snapshot lighting = new LightingSettings.Snapshot(
            LightingSettings.DEFAULT_SUN_QUARTER_STEPS,
            LightingSettings.DEFAULT_STAR_QUARTER_STEPS,
            LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS,
            TransparentNeeMode.DEFAULT);
    DisplaySettings.Snapshot display = new DisplaySettings.Snapshot(
            DisplaySettings.DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS,
            DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS);
    MaterialSettings.Snapshot material = new MaterialSettings.Snapshot(
            MaterialSettings.DEFAULT_ROUGHNESS_STEPS,
            MaterialSettings.DEFAULT_SEAMLESS_GLASS,
            MaterialSettings.DEFAULT_AIR_GAP,
            MaterialSettings.DEFAULT_VANILLA_PBR_PRESETS);
    boolean hdrEnabled;
    int referenceWhiteNits = HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS;
    ReflexMode reflexMode = ReflexMode.OFF;
    boolean dlssFrameGenerationEnabled;
    int dlssFrameGenerationMultiplier = 2;
    boolean dlssFrameGenerationUiRecomposition = true;

    static PrimeConfigData defaults() {
        return new PrimeConfigData();
    }
}
