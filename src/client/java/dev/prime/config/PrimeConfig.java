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
    private static PrimeConfigData data = PrimeConfigData.defaults();
    private static long rendererRevision;
    private static boolean dirty;

    private PrimeConfig() {}

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
                        "Could not read {}; using the default Prime settings", path, exception);
                rewriteNeeded = true;
            }
        }
        data = loaded;
        HdrOutput.setRequested(data.hdrEnabled);
        HdrOutput.setReferenceWhiteNits(data.referenceWhiteNits);
        rendererRevision = 0L;
        dirty = rewriteNeeded;
        PrimeConfigCodec.log(loaded);
    }

    public static RendererSettings rendererSettings() {
        return new RendererSettings(
                data.pathTracingEnabled,
                data.surfaceDetailMode,
                data.voxelTextureSurfaceStrengthSteps,
                data.postProcessingMode,
                data.reconstructionQuality,
                data.astronomy,
                data.lighting,
                data.material,
                data.display,
                data.additionalSpecularBounces,
                data.minimumBounces,
                data.maximumBounces,
                data.terrainWorkerPercentage,
                rendererRevision);
    }

    public static int additionalSpecularBounces() {
        return data.additionalSpecularBounces;
    }

    public static void setAdditionalSpecularBounces(int value) {
        value = SpecularBounceSettings.validateCount(value);
        if (value != data.additionalSpecularBounces) {
            data.additionalSpecularBounces = value;
            rendererChanged();
        }
    }

    public static int minimumBounces() {
        return data.minimumBounces;
    }

    public static void setMinimumBounces(int value) {
        value = MinimumBounceSettings.validateCount(value);
        if (value != data.minimumBounces) {
            data.minimumBounces = value;
            rendererChanged();
        }
    }

    public static int maximumBounces() {
        return data.maximumBounces;
    }

    public static void setMaximumBounces(int value) {
        value = MaximumBounceSettings.validateCount(value);
        if (value != data.maximumBounces) {
            data.maximumBounces = value;
            rendererChanged();
        }
    }

    public static int terrainWorkerPercentage() {
        return data.terrainWorkerPercentage;
    }

    public static void setTerrainWorkerPercentage(int value) {
        value = TerrainWorkerSettings.validatePercentage(value);
        if (value != data.terrainWorkerPercentage) {
            data.terrainWorkerPercentage = value;
            dirty = true;
        }
    }

    public static boolean hdrEnabled() {
        return data.hdrEnabled;
    }

    public static void setHdrEnabled(boolean value) {
        if (value != data.hdrEnabled) {
            data.hdrEnabled = value;
            HdrOutput.setRequested(value);
            dirty = true;
        }
    }

    public static int referenceWhiteNits() {
        return data.referenceWhiteNits;
    }

    public static void setReferenceWhiteNits(int value) {
        value = HdrOutput.validateReferenceWhiteNits(value);
        if (value != data.referenceWhiteNits) {
            data.referenceWhiteNits = value;
            HdrOutput.setReferenceWhiteNits(value);
            dirty = true;
        }
    }

    public static ReflexMode reflexMode() {
        return data.reflexMode;
    }

    public static void setReflexMode(ReflexMode value) {
        Objects.requireNonNull(value, "value");
        if (value != data.reflexMode) {
            data.reflexMode = value;
            dirty = true;
        }
    }

    public static boolean dlssFrameGenerationEnabled() {
        return data.dlssFrameGenerationEnabled;
    }

    public static void setDlssFrameGenerationEnabled(boolean value) {
        if (value != data.dlssFrameGenerationEnabled) {
            data.dlssFrameGenerationEnabled = value;
            dirty = true;
        }
    }

    public static int dlssFrameGenerationMultiplier() {
        return data.dlssFrameGenerationMultiplier;
    }

    public static void setDlssFrameGenerationMultiplier(int value) {
        if (value < 2) {
            throw new IllegalArgumentException("DLSS frame generation multiplier must be at least 2");
        }
        if (value != data.dlssFrameGenerationMultiplier) {
            data.dlssFrameGenerationMultiplier = value;
            dirty = true;
        }
    }

    public static boolean dlssFrameGenerationUiRecomposition() {
        return data.dlssFrameGenerationUiRecomposition;
    }

    public static void setDlssFrameGenerationUiRecomposition(boolean value) {
        if (value != data.dlssFrameGenerationUiRecomposition) {
            data.dlssFrameGenerationUiRecomposition = value;
            dirty = true;
        }
    }

    public static void setPathTracingEnabled(boolean value) {
        if (value != data.pathTracingEnabled) {
            data.pathTracingEnabled = value;
            rendererChanged();
        }
    }

    public static void setSurfaceDetailMode(SurfaceDetailMode value) {
        Objects.requireNonNull(value, "value");
        if (value != data.surfaceDetailMode) {
            data.surfaceDetailMode = value;
            rendererChanged();
        }
    }

    public static void setVoxelTextureSurfaceStrengthSteps(int value) {
        VoxelSurfaceSettings.maximumHeight(value);
        if (value != data.voxelTextureSurfaceStrengthSteps) {
            data.voxelTextureSurfaceStrengthSteps = value;
            rendererChanged();
        }
    }

    public static void setPostProcessingMode(PostProcessingMode value) {
        Objects.requireNonNull(value, "value");
        if (value == PostProcessingMode.DISABLED) {
            throw new IllegalArgumentException("Raw output is a non-persistent session diagnostic");
        }
        if (value != data.postProcessingMode) {
            data.postProcessingMode = value;
            rendererChanged();
        }
    }

    public static void setReconstructionQualityMode(ReconstructionQualityMode value) {
        Objects.requireNonNull(value, "value");
        if (value != data.reconstructionQuality) {
            data.reconstructionQuality = value;
            rendererChanged();
        }
    }

    public static void setLatitudeDegrees(int value) {
        AstronomySettings replacement = data.astronomy.withLatitudeDegrees(value);
        if (replacement != data.astronomy) {
            data.astronomy = replacement;
            rendererChanged();
        }
    }

    public static void setSolarLongitudeDegrees(int value) {
        AstronomySettings replacement = data.astronomy.withSolarLongitudeDegrees(value);
        if (replacement != data.astronomy) {
            data.astronomy = replacement;
            rendererChanged();
        }
    }

    public static void setSunQuarterSteps(int value) {
        LightingSettings.linearMultiplier(value);
        if (value != data.lighting.sunQuarterSteps()) {
            setLighting(new LightingSettings.Snapshot(
                    value,
                    data.lighting.starQuarterSteps(),
                    data.lighting.blockLightQuarterSteps(),
                    data.lighting.transparentNeeMode()));
        }
    }

    public static void setStarQuarterSteps(int value) {
        LightingSettings.linearMultiplier(value);
        if (value != data.lighting.starQuarterSteps()) {
            setLighting(new LightingSettings.Snapshot(
                    data.lighting.sunQuarterSteps(),
                    value,
                    data.lighting.blockLightQuarterSteps(),
                    data.lighting.transparentNeeMode()));
        }
    }

    public static void setBlockLightQuarterSteps(int value) {
        LightingSettings.linearMultiplier(value);
        if (value != data.lighting.blockLightQuarterSteps()) {
            setLighting(new LightingSettings.Snapshot(
                    data.lighting.sunQuarterSteps(),
                    data.lighting.starQuarterSteps(),
                    value,
                    data.lighting.transparentNeeMode()));
        }
    }

    public static void setTransparentNeeMode(TransparentNeeMode value) {
        Objects.requireNonNull(value, "value");
        if (value != data.lighting.transparentNeeMode()) {
            setLighting(new LightingSettings.Snapshot(
                    data.lighting.sunQuarterSteps(),
                    data.lighting.starQuarterSteps(),
                    data.lighting.blockLightQuarterSteps(),
                    value));
        }
    }

    public static void setFinalExposureQuarterSteps(int value) {
        if (value != data.display.finalExposureQuarterSteps()) {
            data.display = new DisplaySettings.Snapshot(
                    value, data.display.autoExposureCompensationSteps());
            rendererChanged();
        }
    }

    public static void setAutoExposureCompensationSteps(int value) {
        if (value != data.display.autoExposureCompensationSteps()) {
            data.display = new DisplaySettings.Snapshot(
                    data.display.finalExposureQuarterSteps(), value);
            rendererChanged();
        }
    }

    public static void setDefaultRoughnessSteps(int value) {
        MaterialSettings.linearRoughness(value);
        if (value != data.material.roughnessSteps()) {
            setMaterial(new MaterialSettings.Snapshot(
                    value,
                    data.material.seamlessGlass(),
                    data.material.airGap(),
                    data.material.vanillaPbrPresets()));
        }
    }

    public static void setSeamlessGlass(boolean value) {
        if (value != data.material.seamlessGlass()) {
            setMaterial(new MaterialSettings.Snapshot(
                    data.material.roughnessSteps(),
                    value,
                    data.material.airGap(),
                    data.material.vanillaPbrPresets()));
        }
    }

    public static void setAirGap(boolean value) {
        if (value != data.material.airGap()) {
            setMaterial(new MaterialSettings.Snapshot(
                    data.material.roughnessSteps(),
                    data.material.seamlessGlass(),
                    value,
                    data.material.vanillaPbrPresets()));
        }
    }

    public static void setVanillaPbrPresets(boolean value) {
        if (value != data.material.vanillaPbrPresets()) {
            setMaterial(new MaterialSettings.Snapshot(
                    data.material.roughnessSteps(),
                    data.material.seamlessGlass(),
                    data.material.airGap(),
                    value));
        }
    }

    public static void restoreDefaults() {
        RendererSettings previous = rendererSettings();
        PrimeConfigData defaults = PrimeConfigData.defaults();
        data = defaults;
        HdrOutput.setRequested(defaults.hdrEnabled);
        HdrOutput.setReferenceWhiteNits(defaults.referenceWhiteNits);
        if (!previous.equals(rendererSettings())) {
            rendererRevision = Math.incrementExact(rendererRevision);
        }
        dirty = true;
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
        return PrimeConfigCodec.encode(data);
    }

    private static void setLighting(LightingSettings.Snapshot value) {
        data.lighting = value;
        rendererChanged();
    }

    private static void setMaterial(MaterialSettings.Snapshot value) {
        data.material = value;
        rendererChanged();
    }

    private static void rendererChanged() {
        rendererRevision = Math.incrementExact(rendererRevision);
        dirty = true;
    }
}
