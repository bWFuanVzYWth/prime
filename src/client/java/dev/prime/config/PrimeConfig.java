package dev.prime.config;

import dev.prime.binding.streamline.ReflexMode;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.HdrOutput;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.MaximumBounceSettings;
import dev.prime.render.MinimumBounceSettings;
import dev.prime.render.RendererSettings;
import dev.prime.render.SpecularBounceSettings;
import dev.prime.render.SurfaceDetailMode;
import dev.prime.render.TransparentNeeMode;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainWorkerSettings;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Client-thread owner of Prime's live settings and renderer revision. */
public final class PrimeConfig {
    // Fabric initializes and mutates video options on the client thread. One immutable snapshot
    // keeps every renderer read coherent without a shared lock or independently mutable globals.
    private static PrimeSettings settings = PrimeSettings.defaults();
    private static int additionalSpecularBounces = SpecularBounceSettings.DEFAULT_COUNT;
    private static int minimumBounces = MinimumBounceSettings.DEFAULT_COUNT;
    private static int maximumBounces = MaximumBounceSettings.DEFAULT_COUNT;
    private static int terrainWorkerPercentage = TerrainWorkerSettings.DEFAULT_PERCENTAGE;
    private static boolean hdrEnabled;
    private static int referenceWhiteNits = HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS;
    private static ReflexMode reflexMode = ReflexMode.OFF;
    private static boolean dlssFrameGenerationEnabled;
    private static int dlssFrameGenerationMultiplier = 2;
    private static boolean dlssFrameGenerationUiRecomposition;
    private static long rendererRevision;
    private static boolean dirty;

    private PrimeConfig() {
    }

    public static void load() {
        Path path = PrimeConfigFile.path();
        PrimeConfigData loaded = PrimeConfigData.defaults();
        boolean rewriteNeeded = false;
        if (PrimeConfigFile.exists(path)) {
            try {
                PrimeConfigCodec.DecodeResult decoded =
                        PrimeConfigCodec.decode(PrimeConfigFile.read(path));
                loaded = decoded.data();
                rewriteNeeded = decoded.rewriteNeeded();
            } catch (IOException | IllegalArgumentException exception) {
                PrimeInfo.LOGGER.warn(
                        "Could not read {}; using the default Prime settings",
                        path,
                        exception);
                rewriteNeeded = true;
            }
        }
        applyLoaded(loaded, rewriteNeeded);
        PrimeConfigCodec.log(loaded);
    }

    private static void applyLoaded(PrimeConfigData loaded, boolean rewriteNeeded) {
        settings = loaded.settings();
        additionalSpecularBounces = loaded.additionalSpecularBounces();
        minimumBounces = loaded.minimumBounces();
        maximumBounces = loaded.maximumBounces();
        terrainWorkerPercentage = loaded.terrainWorkerPercentage();
        hdrEnabled = loaded.hdrEnabled();
        HdrOutput.setRequested(hdrEnabled);
        referenceWhiteNits = loaded.referenceWhiteNits();
        HdrOutput.setReferenceWhiteNits(referenceWhiteNits);
        reflexMode = loaded.reflexMode();
        dlssFrameGenerationEnabled = loaded.dlssFrameGenerationEnabled();
        dlssFrameGenerationMultiplier = loaded.dlssFrameGenerationMultiplier();
        dlssFrameGenerationUiRecomposition = loaded.dlssFrameGenerationUiRecomposition();
        rendererRevision = 0L;
        dirty = rewriteNeeded;
    }

    public static PrimeSettings settings() {
        return settings;
    }

    public static RendererSettings rendererSettings() {
        PrimeSettings current = settings;
        long revision = rendererRevision;
        return rendererSettings(current, revision);
    }

    static RendererSettings rendererSettings(PrimeSettings current, long revision) {
        return new RendererSettings(
                current.pathTracingEnabled(),
                current.surfaceDetailMode(),
                current.voxelTextureSurfaceStrengthSteps(),
                current.postProcessingMode(),
                current.reconstructionQuality(),
                current.astronomy(),
                current.lighting(),
                current.material(),
                current.display(),
                additionalSpecularBounces,
                minimumBounces,
                maximumBounces,
                terrainWorkerPercentage,
                revision);
    }

    public static void setPathTracingEnabled(boolean enabled) {
        update(settings.withPathTracingEnabled(enabled));
    }

    public static int additionalSpecularBounces() {
        return additionalSpecularBounces;
    }

    public static void setAdditionalSpecularBounces(int bounces) {
        int replacement = SpecularBounceSettings.validateCount(bounces);
        if (replacement != additionalSpecularBounces) {
            additionalSpecularBounces = replacement;
            rendererRevision = Math.incrementExact(rendererRevision);
            dirty = true;
        }
    }

    public static int minimumBounces() {
        return minimumBounces;
    }

    public static void setMinimumBounces(int bounces) {
        int replacement = MinimumBounceSettings.validateCount(bounces);
        if (replacement != minimumBounces) {
            minimumBounces = replacement;
            rendererRevision = Math.incrementExact(rendererRevision);
            dirty = true;
        }
    }

    public static int maximumBounces() {
        return maximumBounces;
    }

    public static void setMaximumBounces(int bounces) {
        int replacement = MaximumBounceSettings.validateCount(bounces);
        if (replacement != maximumBounces) {
            maximumBounces = replacement;
            rendererRevision = Math.incrementExact(rendererRevision);
            dirty = true;
        }
    }

    public static int terrainWorkerPercentage() {
        return terrainWorkerPercentage;
    }

    public static boolean hdrEnabled() {
        return hdrEnabled;
    }

    public static void setHdrEnabled(boolean enabled) {
        if (enabled != hdrEnabled) {
            hdrEnabled = enabled;
            HdrOutput.setRequested(enabled);
            dirty = true;
        }
    }

    public static int referenceWhiteNits() {
        return referenceWhiteNits;
    }

    public static void setReferenceWhiteNits(int value) {
        int replacement = HdrOutput.validateReferenceWhiteNits(value);
        if (replacement != referenceWhiteNits) {
            referenceWhiteNits = replacement;
            HdrOutput.setReferenceWhiteNits(replacement);
            dirty = true;
        }
    }

    public static ReflexMode reflexMode() {
        return reflexMode;
    }

    public static void setReflexMode(ReflexMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (mode != reflexMode) {
            reflexMode = mode;
            dirty = true;
        }
    }

    public static boolean dlssFrameGenerationEnabled() {
        return dlssFrameGenerationEnabled;
    }

    public static void setDlssFrameGenerationEnabled(boolean enabled) {
        if (enabled != dlssFrameGenerationEnabled) {
            dlssFrameGenerationEnabled = enabled;
            dirty = true;
        }
    }

    public static int dlssFrameGenerationMultiplier() {
        return dlssFrameGenerationMultiplier;
    }

    public static void setDlssFrameGenerationMultiplier(int multiplier) {
        if (multiplier < 2) {
            throw new IllegalArgumentException("DLSS frame generation multiplier must be at least 2");
        }
        if (multiplier != dlssFrameGenerationMultiplier) {
            dlssFrameGenerationMultiplier = multiplier;
            dirty = true;
        }
    }

    public static boolean dlssFrameGenerationUiRecomposition() {
        return dlssFrameGenerationUiRecomposition;
    }

    public static void setDlssFrameGenerationUiRecomposition(boolean enabled) {
        if (enabled != dlssFrameGenerationUiRecomposition) {
            dlssFrameGenerationUiRecomposition = enabled;
            dirty = true;
        }
    }

    public static void setTerrainWorkerPercentage(int percentage) {
        int replacement = TerrainWorkerSettings.validatePercentage(percentage);
        if (replacement != terrainWorkerPercentage) {
            terrainWorkerPercentage = replacement;
            dirty = true;
        }
    }

    public static void setSurfaceDetailMode(SurfaceDetailMode mode) {
        update(settings.withSurfaceDetailMode(mode));
    }

    public static void setVoxelTextureSurfaceStrengthSteps(int steps) {
        update(settings.withVoxelTextureSurfaceStrengthSteps(steps));
    }

    public static void setPostProcessingMode(PostProcessingMode mode) {
        update(settings.withPostProcessingMode(mode));
    }

    public static void setReconstructionQualityMode(ReconstructionQualityMode mode) {
        update(settings.withReconstructionQuality(mode));
    }

    public static void setLatitudeDegrees(int degrees) {
        update(settings.withLatitudeDegrees(degrees));
    }

    public static void setSolarLongitudeDegrees(int degrees) {
        update(settings.withSolarLongitudeDegrees(degrees));
    }

    public static void setSunQuarterSteps(int quarterSteps) {
        update(settings.withSunQuarterSteps(quarterSteps));
    }

    public static void setStarQuarterSteps(int quarterSteps) {
        update(settings.withStarQuarterSteps(quarterSteps));
    }

    public static void setBlockLightQuarterSteps(int quarterSteps) {
        update(settings.withBlockLightQuarterSteps(quarterSteps));
    }

    public static void setTransparentNeeMode(TransparentNeeMode mode) {
        update(settings.withTransparentNeeMode(mode));
    }

    public static void setFinalExposureQuarterSteps(int quarterSteps) {
        update(settings.withFinalExposureQuarterSteps(quarterSteps));
    }

    public static void setAutoExposureCompensationSteps(int steps) {
        update(settings.withAutoExposureCompensationSteps(steps));
    }

    public static void setDefaultRoughnessSteps(int steps) {
        update(settings.withDefaultRoughnessSteps(steps));
    }

    public static void setSeamlessGlass(boolean enabled) {
        update(settings.withSeamlessGlass(enabled));
    }

    public static void setAirGap(boolean enabled) {
        update(settings.withAirGap(enabled));
    }

    public static void setVanillaPbrPresets(boolean enabled) {
        update(settings.withVanillaPbrPresets(enabled));
    }

    public static void restoreDefaults() {
        update(restoredDefaults(settings));
        setAdditionalSpecularBounces(SpecularBounceSettings.DEFAULT_COUNT);
        setMinimumBounces(MinimumBounceSettings.DEFAULT_COUNT);
        setMaximumBounces(MaximumBounceSettings.DEFAULT_COUNT);
        setTerrainWorkerPercentage(TerrainWorkerSettings.DEFAULT_PERCENTAGE);
        setHdrEnabled(false);
        setReferenceWhiteNits(HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS);
        setReflexMode(ReflexMode.OFF);
        setDlssFrameGenerationEnabled(false);
        setDlssFrameGenerationMultiplier(2);
        setDlssFrameGenerationUiRecomposition(false);
    }

    static PrimeSettings restoredDefaults(PrimeSettings current) {
        return current
                .withPathTracingEnabled(true)
                .withSurfaceDetailMode(SurfaceDetailMode.DEFAULT)
                .withVoxelTextureSurfaceStrengthSteps(VoxelSurfaceSettings.DEFAULT_STEPS)
                .withPostProcessingMode(PostProcessingMode.DEFAULT)
                .withReconstructionQuality(ReconstructionQualityMode.DEFAULT)
                .withLatitudeDegrees(AstronomySettings.DEFAULT_LATITUDE_DEGREES)
                .withSolarLongitudeDegrees(AstronomySettings.DEFAULT_SOLAR_LONGITUDE_DEGREES)
                .withSunQuarterSteps(LightingSettings.DEFAULT_SUN_QUARTER_STEPS)
                .withStarQuarterSteps(LightingSettings.DEFAULT_STAR_QUARTER_STEPS)
                .withBlockLightQuarterSteps(LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS)
                .withTransparentNeeMode(TransparentNeeMode.DEFAULT)
                .withFinalExposureQuarterSteps(
                        DisplaySettings.DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS)
                .withAutoExposureCompensationSteps(
                        DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS)
                .withDefaultRoughnessSteps(MaterialSettings.DEFAULT_ROUGHNESS_STEPS)
                .withSeamlessGlass(MaterialSettings.DEFAULT_SEAMLESS_GLASS)
                .withAirGap(MaterialSettings.DEFAULT_AIR_GAP)
                .withVanillaPbrPresets(MaterialSettings.DEFAULT_VANILLA_PBR_PRESETS);
    }

    public static void save() {
        Path path = PrimeConfigFile.path();
        if (!dirty && PrimeConfigFile.exists(path)) {
            return;
        }
        try {
            PrimeConfigFile.write(path, serializedContents());
            dirty = false;
        } catch (IOException exception) {
            PrimeInfo.LOGGER.error("Could not save Prime settings to {}", path, exception);
        }
    }

    static String serializedContents() {
        return PrimeConfigCodec.encode(currentData());
    }

    private static PrimeConfigData currentData() {
        return new PrimeConfigData(
                settings,
                additionalSpecularBounces,
                minimumBounces,
                maximumBounces,
                terrainWorkerPercentage,
                hdrEnabled,
                referenceWhiteNits,
                reflexMode,
                dlssFrameGenerationEnabled,
                dlssFrameGenerationMultiplier,
                dlssFrameGenerationUiRecomposition);
    }

    private static void update(PrimeSettings replacement) {
        if (replacement != settings) {
            settings = replacement;
            rendererRevision = Math.incrementExact(rendererRevision);
            dirty = true;
        }
    }
}
