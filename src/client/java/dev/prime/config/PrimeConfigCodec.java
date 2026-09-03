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
import dev.prime.render.SpecularBounceSettings;
import dev.prime.render.SurfaceDetailMode;
import dev.prime.render.TransparentNeeMode;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainWorkerSettings;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

/** Scalar codec and validation for the current Prime properties format. */
final class PrimeConfigCodec {
    private static final String PATH_TRACING_ENABLED_KEY = "renderer.path_tracing";
    private static final String ADDITIONAL_SPECULAR_BOUNCES_KEY =
            "renderer.additional_specular_bounces";
    private static final String MINIMUM_BOUNCES_KEY = "renderer.minimum_bounces";
    private static final String MAXIMUM_BOUNCES_KEY = "renderer.maximum_bounces";
    private static final String LEGACY_SPECULAR_BOUNCES_KEY = "renderer.primary_chain_limit";
    private static final String LEGACY_MINIMUM_BOUNCES_KEY =
            "renderer.wavefront_prefix_rounds";
    private static final String LEGACY_MAXIMUM_BOUNCES_KEY = "renderer.scatter_count";
    private static final String TERRAIN_WORKER_PERCENTAGE_KEY = "terrain.worker_percentage";
    private static final String SURFACE_DETAIL_MODE_KEY = "material.surface_detail";
    private static final String VOXEL_TEXTURE_SURFACE_STRENGTH_KEY =
            "material.displacement_height";
    private static final String MODE_KEY = "post_processing.mode";
    private static final String QUALITY_KEY = "post_processing.quality";
    private static final String SUN_EV_KEY = "lighting.sun_ev";
    private static final String STAR_EV_KEY = "lighting.star_ev";
    private static final String BLOCK_LIGHT_EV_KEY = "lighting.block_light_ev";
    private static final String TRANSPARENT_NEE_MODE_KEY = "lighting.transparent_nee_mode";
    private static final String LATITUDE_DEGREES_KEY = "astronomy.latitude_degrees";
    private static final String SOLAR_LONGITUDE_DEGREES_KEY =
            "astronomy.solar_longitude_degrees";
    private static final String FINAL_EXPOSURE_EV_KEY = "display.final_exposure_ev";
    private static final String HDR_ENABLED_KEY = "display.hdr";
    private static final String AUTO_EXPOSURE_COMPENSATION_KEY =
            "display.auto_exposure_compensation";
    private static final String REFERENCE_WHITE_NITS_KEY = "display.reference_white_nits";
    private static final String DEFAULT_ROUGHNESS_KEY = "material.default_roughness";
    private static final String SEAMLESS_GLASS_KEY = "material.seamless_glass";
    private static final String AIR_GAP_KEY = "material.air_gap";
    private static final String VANILLA_PBR_PRESETS_KEY = "material.vanilla_pbr_presets";
    private static final String REFLEX_MODE_KEY = "low_latency.reflex_mode";
    private static final String DLSS_FRAME_GENERATION_ENABLED_KEY =
            "streamline.dlss_frame_generation";
    private static final String DLSS_FRAME_GENERATION_MULTIPLIER_KEY =
            "streamline.dlss_frame_generation_multiplier";
    private static final String DLSS_FRAME_GENERATION_UI_RECOMPOSITION_KEY =
            "streamline.dlss_frame_generation_ui_recomposition";
    private static final Set<String> CURRENT_KEYS = Set.copyOf(
            encode(PrimeConfigData.defaults()).lines()
                    .map(line -> line.substring(0, line.indexOf('=')))
                    .toList());

    private PrimeConfigCodec() {
    }

    static DecodeResult decode(Properties properties) {
        Reader reader = new Reader(properties);
        PrimeConfigData data = PrimeConfigData.defaults();
        data.pathTracingEnabled = reader.value(
                PATH_TRACING_ENABLED_KEY, data.pathTracingEnabled,
                PrimeConfigCodec::parseBoolean, "path-tracing switch");
        data.additionalSpecularBounces = reader.migratedValue(
                ADDITIONAL_SPECULAR_BOUNCES_KEY, LEGACY_SPECULAR_BOUNCES_KEY,
                data.additionalSpecularBounces,
                PrimeConfigCodec::parseAdditionalSpecularBounces,
                "additional specular bounce count");
        data.minimumBounces = reader.migratedValue(
                MINIMUM_BOUNCES_KEY, LEGACY_MINIMUM_BOUNCES_KEY,
                data.minimumBounces, PrimeConfigCodec::parseMinimumBounces,
                "minimum bounce count");
        data.maximumBounces = reader.migratedValue(
                MAXIMUM_BOUNCES_KEY, LEGACY_MAXIMUM_BOUNCES_KEY,
                data.maximumBounces, PrimeConfigCodec::parseMaximumBounces,
                "maximum bounce count");
        data.terrainWorkerPercentage = reader.value(
                TERRAIN_WORKER_PERCENTAGE_KEY, data.terrainWorkerPercentage,
                PrimeConfigCodec::parseTerrainWorkerPercentage,
                "terrain worker percentage");
        data.surfaceDetailMode = reader.value(
                SURFACE_DETAIL_MODE_KEY, data.surfaceDetailMode,
                PrimeConfigCodec::parseSurfaceDetailMode, "surface-detail mode");
        data.voxelTextureSurfaceStrengthSteps = reader.value(
                VOXEL_TEXTURE_SURFACE_STRENGTH_KEY,
                data.voxelTextureSurfaceStrengthSteps,
                PrimeConfigCodec::parseVoxelSurfaceStrengthSteps,
                "voxel-surface strength");
        data.postProcessingMode = reader.value(
                MODE_KEY, data.postProcessingMode,
                PrimeConfigCodec::parsePersistentMode, "post-processing mode");
        data.reconstructionQuality = reader.value(
                QUALITY_KEY, data.reconstructionQuality,
                PrimeConfigCodec::parseQuality, "reconstruction quality");
        data.astronomy = new AstronomySettings(
                reader.value(
                        LATITUDE_DEGREES_KEY, data.astronomy.latitudeDegrees(),
                        PrimeConfigCodec::parseLatitudeDegrees, "observer latitude"),
                reader.value(
                        SOLAR_LONGITUDE_DEGREES_KEY, data.astronomy.solarLongitudeDegrees(),
                        PrimeConfigCodec::parseSolarLongitudeDegrees, "solar longitude"));
        data.lighting = new LightingSettings.Snapshot(
                reader.value(
                        SUN_EV_KEY, data.lighting.sunQuarterSteps(),
                        PrimeConfigCodec::parseEvQuarterSteps, "sun exposure"),
                reader.value(
                        STAR_EV_KEY, data.lighting.starQuarterSteps(),
                        PrimeConfigCodec::parseStarEvQuarterSteps, "star exposure"),
                reader.value(
                        BLOCK_LIGHT_EV_KEY, data.lighting.blockLightQuarterSteps(),
                        PrimeConfigCodec::parseEvQuarterSteps, "block-light exposure"),
                reader.value(
                        TRANSPARENT_NEE_MODE_KEY, data.lighting.transparentNeeMode(),
                        PrimeConfigCodec::parseTransparentNeeMode, "transparent NEE mode"));
        data.display = new DisplaySettings.Snapshot(
                reader.value(
                        FINAL_EXPOSURE_EV_KEY, data.display.finalExposureQuarterSteps(),
                        PrimeConfigCodec::parseFinalExposureQuarterSteps, "final exposure"),
                reader.value(
                        AUTO_EXPOSURE_COMPENSATION_KEY,
                        data.display.autoExposureCompensationSteps(),
                        PrimeConfigCodec::parseAutoExposureCompensationSteps,
                        "auto-exposure compensation"));
        data.hdrEnabled = reader.value(
                HDR_ENABLED_KEY, data.hdrEnabled,
                PrimeConfigCodec::parseBoolean, "HDR switch");
        data.referenceWhiteNits = reader.value(
                REFERENCE_WHITE_NITS_KEY, data.referenceWhiteNits,
                PrimeConfigCodec::parseReferenceWhiteNits, "HDR reference white");
        data.material = new MaterialSettings.Snapshot(
                reader.value(
                        DEFAULT_ROUGHNESS_KEY, data.material.roughnessSteps(),
                        PrimeConfigCodec::parseRoughnessSteps, "default material roughness"),
                reader.value(
                        SEAMLESS_GLASS_KEY, data.material.seamlessGlass(),
                        PrimeConfigCodec::parseBoolean, "seamless-glass switch"),
                reader.value(
                        AIR_GAP_KEY, data.material.airGap(),
                        PrimeConfigCodec::parseBoolean, "air-gap switch"),
                reader.value(
                        VANILLA_PBR_PRESETS_KEY, data.material.vanillaPbrPresets(),
                        PrimeConfigCodec::parseBoolean, "vanilla-PBR preset switch"));
        data.reflexMode = reader.value(
                REFLEX_MODE_KEY, data.reflexMode,
                PrimeConfigCodec::parseReflexMode, "Reflex mode");
        data.dlssFrameGenerationEnabled = reader.value(
                DLSS_FRAME_GENERATION_ENABLED_KEY, data.dlssFrameGenerationEnabled,
                PrimeConfigCodec::parseBoolean, "DLSS frame-generation switch");
        data.dlssFrameGenerationMultiplier = reader.value(
                DLSS_FRAME_GENERATION_MULTIPLIER_KEY,
                data.dlssFrameGenerationMultiplier,
                PrimeConfigCodec::parseDlssFrameGenerationMultiplier,
                "DLSS frame-generation multiplier");
        data.dlssFrameGenerationUiRecomposition = reader.value(
                DLSS_FRAME_GENERATION_UI_RECOMPOSITION_KEY,
                data.dlssFrameGenerationUiRecomposition,
                PrimeConfigCodec::parseBoolean,
                "DLSS frame-generation UI recomposition switch");

        reader.rewriteNeeded |= !properties.stringPropertyNames().equals(CURRENT_KEYS);
        return new DecodeResult(data, reader.rewriteNeeded);
    }

    static String encode(PrimeConfigData data) {
        return PATH_TRACING_ENABLED_KEY + "=" + data.pathTracingEnabled + "\n"
                + ADDITIONAL_SPECULAR_BOUNCES_KEY + "="
                + data.additionalSpecularBounces + "\n"
                + MINIMUM_BOUNCES_KEY + "=" + data.minimumBounces + "\n"
                + MAXIMUM_BOUNCES_KEY + "=" + data.maximumBounces + "\n"
                + TERRAIN_WORKER_PERCENTAGE_KEY + "="
                + data.terrainWorkerPercentage + "\n"
                + SURFACE_DETAIL_MODE_KEY + "="
                + data.surfaceDetailMode.id() + "\n"
                + VOXEL_TEXTURE_SURFACE_STRENGTH_KEY + "="
                + formatVoxelSurfaceStrength(data.voxelTextureSurfaceStrengthSteps) + "\n"
                + MODE_KEY + "=" + data.postProcessingMode.id() + "\n"
                + QUALITY_KEY + "=" + data.reconstructionQuality.id() + "\n"
                + LATITUDE_DEGREES_KEY + "=" + data.astronomy.latitudeDegrees() + "\n"
                + SOLAR_LONGITUDE_DEGREES_KEY + "="
                + data.astronomy.solarLongitudeDegrees() + "\n"
                + SUN_EV_KEY + "=" + formatEv(data.lighting.sunQuarterSteps()) + "\n"
                + STAR_EV_KEY + "=" + formatStarEv(data.lighting.starQuarterSteps()) + "\n"
                + BLOCK_LIGHT_EV_KEY + "=" + formatEv(data.lighting.blockLightQuarterSteps()) + "\n"
                + TRANSPARENT_NEE_MODE_KEY + "=" + data.lighting.transparentNeeMode().id() + "\n"
                + FINAL_EXPOSURE_EV_KEY + "="
                + formatFinalExposure(data.display.finalExposureQuarterSteps()) + "\n"
                + HDR_ENABLED_KEY + "=" + data.hdrEnabled + "\n"
                + REFERENCE_WHITE_NITS_KEY + "=" + data.referenceWhiteNits + "\n"
                + AUTO_EXPOSURE_COMPENSATION_KEY + "="
                + formatAutoExposureCompensation(data.display.autoExposureCompensationSteps()) + "\n"
                + DEFAULT_ROUGHNESS_KEY + "="
                + formatRoughness(data.material.roughnessSteps()) + "\n"
                + SEAMLESS_GLASS_KEY + "=" + data.material.seamlessGlass() + "\n"
                + AIR_GAP_KEY + "=" + data.material.airGap() + "\n"
                + VANILLA_PBR_PRESETS_KEY + "=" + data.material.vanillaPbrPresets() + "\n"
                + REFLEX_MODE_KEY + "=" + data.reflexMode.name().toLowerCase(Locale.ROOT) + "\n"
                + DLSS_FRAME_GENERATION_ENABLED_KEY + "=" + data.dlssFrameGenerationEnabled + "\n"
                + DLSS_FRAME_GENERATION_MULTIPLIER_KEY + "=" + data.dlssFrameGenerationMultiplier + "\n"
                + DLSS_FRAME_GENERATION_UI_RECOMPOSITION_KEY + "="
                + data.dlssFrameGenerationUiRecomposition + "\n";
    }

    static void log(PrimeConfigData data) {
        PrimeInfo.LOGGER.info(
                "Prime settings: path tracing {}, additional specular bounces {}, minimum bounces {}, maximum bounces {}, terrain workers {}%, surface detail {} at {}x displacement height, post-processing {} quality {} (NRD-FSR {}x), latitude {} degrees, solar longitude {} degrees, sun {} EV, stars {} EV, block lights {} EV, transparent NEE {}, final exposure {} EV, HDR {}, reference white {}, auto-exposure compensation {}, default roughness {}, seamless glass {}, air gap {}, vanilla PBR presets {}, Reflex {}, DLSS frame generation {}, multiplier {}x, UI recomposition {}",
                data.pathTracingEnabled ? "enabled" : "disabled",
                data.additionalSpecularBounces,
                data.minimumBounces,
                data.maximumBounces,
                data.terrainWorkerPercentage,
                data.surfaceDetailMode.id(),
                formatVoxelSurfaceStrength(data.voxelTextureSurfaceStrengthSteps),
                data.postProcessingMode.id(),
                data.reconstructionQuality.id(),
                data.reconstructionQuality.upscaleRatio(),
                data.astronomy.latitudeDegrees(),
                data.astronomy.solarLongitudeDegrees(),
                formatEv(data.lighting.sunQuarterSteps()),
                formatStarEv(data.lighting.starQuarterSteps()),
                formatEv(data.lighting.blockLightQuarterSteps()),
                data.lighting.transparentNeeMode().id(),
                formatFinalExposure(data.display.finalExposureQuarterSteps()),
                data.hdrEnabled ? "enabled" : "disabled",
                data.referenceWhiteNits == HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS
                        ? "automatic"
                        : data.referenceWhiteNits + " nits",
                formatAutoExposureCompensation(data.display.autoExposureCompensationSteps()),
                formatRoughness(data.material.roughnessSteps()),
                data.material.seamlessGlass() ? "enabled" : "disabled",
                data.material.airGap() ? "enabled" : "disabled",
                data.material.vanillaPbrPresets() ? "enabled" : "disabled",
                data.reflexMode.name().toLowerCase(Locale.ROOT),
                data.dlssFrameGenerationEnabled ? "enabled" : "disabled",
                data.dlssFrameGenerationMultiplier,
                data.dlssFrameGenerationUiRecomposition ? "enabled" : "disabled");
    }

    static ReflexMode parseReflexMode(String value) {
        try {
            return ReflexMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown Reflex mode", exception);
        }
    }

    private static PostProcessingMode parsePersistentMode(String value) {
        PostProcessingMode mode = PostProcessingMode.findById(value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown post-processing mode"));
        if (mode == PostProcessingMode.DISABLED) {
            throw new IllegalArgumentException("Raw output is a session diagnostic");
        }
        return mode;
    }

    static SurfaceDetailMode parseSurfaceDetailMode(String value) {
        return SurfaceDetailMode.findById(value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown surface-detail mode"));
    }

    private static ReconstructionQualityMode parseQuality(String value) {
        return ReconstructionQualityMode.findById(value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown reconstruction quality"));
    }

    static TransparentNeeMode parseTransparentNeeMode(String value) {
        return TransparentNeeMode.findById(value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown transparent NEE mode"));
    }

    static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("Boolean setting must be true or false");
    }

    static int parseDlssFrameGenerationMultiplier(String value) {
        return parseInteger(value, multiplier -> {
            if (multiplier < 2) {
                throw new IllegalArgumentException(
                        "DLSS frame-generation multiplier must be at least 2");
            }
            return multiplier;
        }, "DLSS frame-generation multiplier must be an integer");
    }

    static int parseMaximumBounces(String value) {
        return parseInteger(
                value,
                MaximumBounceSettings::validateCount,
                "Maximum bounce count must be an integer");
    }

    static int parseAdditionalSpecularBounces(String value) {
        return parseInteger(
                value,
                SpecularBounceSettings::validateCount,
                "Additional specular bounce count must be an integer");
    }

    static int parseMinimumBounces(String value) {
        return parseInteger(
                value,
                MinimumBounceSettings::validateCount,
                "Minimum bounce count must be an integer");
    }

    static int parseTerrainWorkerPercentage(String value) {
        return parseInteger(
                value,
                TerrainWorkerSettings::validatePercentage,
                "Terrain worker percentage must be an integer");
    }

    static int parseLatitudeDegrees(String value) {
        return parseInteger(
                value,
                latitude -> new AstronomySettings(
                        latitude,
                        AstronomySettings.DEFAULT_SOLAR_LONGITUDE_DEGREES).latitudeDegrees(),
                "Observer latitude must be an integer degree");
    }

    static int parseSolarLongitudeDegrees(String value) {
        return parseInteger(
                value,
                longitude -> new AstronomySettings(
                        AstronomySettings.DEFAULT_LATITUDE_DEGREES,
                        longitude).solarLongitudeDegrees(),
                "Solar longitude must be an integer degree");
    }

    static int parseEvQuarterSteps(String value) {
        return parseSteps(
                value,
                LightingSettings.QUARTER_STEPS_PER_EV,
                LightingSettings::linearMultiplier,
                "EV must be an exact 0.25-EV step");
    }

    static int parseVoxelSurfaceStrengthSteps(String value) {
        return parseSteps(
                value,
                VoxelSurfaceSettings.STEPS_PER_UNIT,
                VoxelSurfaceSettings::maximumHeight,
                "Voxel-surface strength must be an exact 0.01 step");
    }

    static String formatVoxelSurfaceStrength(int steps) {
        return formatSteps(
                steps,
                VoxelSurfaceSettings.STEPS_PER_UNIT,
                VoxelSurfaceSettings::maximumHeight);
    }

    static String formatEv(int quarterSteps) {
        return formatSteps(
                quarterSteps,
                LightingSettings.QUARTER_STEPS_PER_EV,
                LightingSettings::linearMultiplier);
    }

    static int parseStarEvQuarterSteps(String value) {
        return parseSteps(
                value,
                LightingSettings.QUARTER_STEPS_PER_EV,
                LightingSettings::starLinearMultiplier,
                "Star EV must be an exact 0.25-EV step");
    }

    static String formatStarEv(int quarterSteps) {
        return formatSteps(
                quarterSteps,
                LightingSettings.QUARTER_STEPS_PER_EV,
                LightingSettings::starLinearMultiplier);
    }

    static int parseFinalExposureQuarterSteps(String value) {
        return parseSteps(
                value,
                DisplaySettings.QUARTER_STEPS_PER_EV,
                DisplaySettings::finalExposureMultiplier,
                "Final exposure must be an exact 0.25-EV step");
    }

    static String formatFinalExposure(int quarterSteps) {
        return formatSteps(
                quarterSteps,
                DisplaySettings.QUARTER_STEPS_PER_EV,
                DisplaySettings::finalExposureMultiplier);
    }

    static int parseAutoExposureCompensationSteps(String value) {
        return parseSteps(
                value,
                DisplaySettings.HUNDREDTH_STEPS_PER_UNIT,
                DisplaySettings::autoExposureCompensation,
                "Auto-exposure compensation must be an exact 0.01 step");
    }

    static String formatAutoExposureCompensation(int steps) {
        return formatSteps(
                steps,
                DisplaySettings.HUNDREDTH_STEPS_PER_UNIT,
                DisplaySettings::autoExposureCompensation);
    }

    static int parseReferenceWhiteNits(String value) {
        return parseInteger(
                value,
                HdrOutput::validateReferenceWhiteNits,
                "HDR reference white must be an integer number of nits");
    }

    private static int parseInteger(
            String value, IntUnaryOperator validator, String error) {
        try {
            return validator.applyAsInt(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(error, exception);
        }
    }

    static int parseRoughnessSteps(String value) {
        return parseSteps(
                value,
                MaterialSettings.STEPS_PER_UNIT,
                MaterialSettings::linearRoughness,
                "Default material roughness must be an exact 0.01 step");
    }

    static String formatRoughness(int steps) {
        return formatSteps(
                steps,
                MaterialSettings.STEPS_PER_UNIT,
                MaterialSettings::linearRoughness);
    }

    private static int parseSteps(
            String value, int stepsPerUnit, IntConsumer validator, String error) {
        try {
            int steps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(stepsPerUnit))
                    .intValueExact();
            validator.accept(steps);
            return steps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(error, exception);
        }
    }

    private static String formatSteps(
            int steps, int stepsPerUnit, IntConsumer validator) {
        validator.accept(steps);
        return BigDecimal.valueOf(steps)
                .divide(BigDecimal.valueOf(stepsPerUnit))
                .toPlainString();
    }

    record DecodeResult(PrimeConfigData data, boolean rewriteNeeded) {
    }

    private static final class Reader {
        private final Properties properties;
        private boolean rewriteNeeded;

        private Reader(Properties properties) {
            this.properties = properties;
        }

        private <T> T value(
                String key,
                T fallback,
                Function<String, T> parser,
                String label) {
            return this.parse(this.properties.getProperty(key), fallback, parser, label);
        }

        private <T> T migratedValue(
                String key,
                String legacyKey,
                T fallback,
                Function<String, T> parser,
                String label) {
            String encoded = this.properties.getProperty(key);
            if (encoded == null) {
                this.rewriteNeeded = true;
                encoded = this.properties.getProperty(legacyKey);
            }
            return this.parse(encoded, fallback, parser, label);
        }

        private <T> T parse(
                String encoded,
                T fallback,
                Function<String, T> parser,
                String label) {
            if (encoded == null) {
                this.rewriteNeeded = true;
                return fallback;
            }
            try {
                return parser.apply(encoded);
            } catch (IllegalArgumentException exception) {
                PrimeInfo.LOGGER.warn(
                        "Invalid Prime {} '{}'; using the default",
                        label,
                        encoded);
                this.rewriteNeeded = true;
                return fallback;
            }
        }
    }
}
