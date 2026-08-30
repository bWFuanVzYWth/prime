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
    private static final Set<String> CURRENT_KEYS = Set.of(
            PATH_TRACING_ENABLED_KEY,
            ADDITIONAL_SPECULAR_BOUNCES_KEY,
            MINIMUM_BOUNCES_KEY,
            MAXIMUM_BOUNCES_KEY,
            TERRAIN_WORKER_PERCENTAGE_KEY,
            SURFACE_DETAIL_MODE_KEY,
            VOXEL_TEXTURE_SURFACE_STRENGTH_KEY,
            MODE_KEY,
            QUALITY_KEY,
            SUN_EV_KEY,
            STAR_EV_KEY,
            BLOCK_LIGHT_EV_KEY,
            TRANSPARENT_NEE_MODE_KEY,
            LATITUDE_DEGREES_KEY,
            SOLAR_LONGITUDE_DEGREES_KEY,
            FINAL_EXPOSURE_EV_KEY,
            HDR_ENABLED_KEY,
            AUTO_EXPOSURE_COMPENSATION_KEY,
            REFERENCE_WHITE_NITS_KEY,
            DEFAULT_ROUGHNESS_KEY,
            SEAMLESS_GLASS_KEY,
            AIR_GAP_KEY,
            VANILLA_PBR_PRESETS_KEY,
            REFLEX_MODE_KEY,
            DLSS_FRAME_GENERATION_ENABLED_KEY,
            DLSS_FRAME_GENERATION_MULTIPLIER_KEY,
            DLSS_FRAME_GENERATION_UI_RECOMPOSITION_KEY);

    private PrimeConfigCodec() {
    }

    static DecodeResult decode(Properties properties) {
        Reader reader = new Reader(properties);
        PrimeConfigData defaults = PrimeConfigData.defaults();
        PrimeSettings defaultSettings = defaults.settings();
        boolean pathTracing = reader.value(
                PATH_TRACING_ENABLED_KEY,
                defaultSettings.pathTracingEnabled(),
                PrimeConfigCodec::parseBoolean,
                "path-tracing switch");
        int additionalSpecularBounces = reader.migratedValue(
                ADDITIONAL_SPECULAR_BOUNCES_KEY,
                LEGACY_SPECULAR_BOUNCES_KEY,
                defaults.additionalSpecularBounces(),
                PrimeConfigCodec::parseAdditionalSpecularBounces,
                "additional specular bounce count");
        int minimumBounces = reader.migratedValue(
                MINIMUM_BOUNCES_KEY,
                LEGACY_MINIMUM_BOUNCES_KEY,
                defaults.minimumBounces(),
                PrimeConfigCodec::parseMinimumBounces,
                "minimum bounce count");
        int maximumBounces = reader.migratedValue(
                MAXIMUM_BOUNCES_KEY,
                LEGACY_MAXIMUM_BOUNCES_KEY,
                defaults.maximumBounces(),
                PrimeConfigCodec::parseMaximumBounces,
                "maximum bounce count");
        int terrainWorkers = reader.value(
                TERRAIN_WORKER_PERCENTAGE_KEY,
                defaults.terrainWorkerPercentage(),
                PrimeConfigCodec::parseTerrainWorkerPercentage,
                "terrain worker percentage");
        SurfaceDetailMode surfaceDetail = reader.value(
                SURFACE_DETAIL_MODE_KEY,
                defaultSettings.surfaceDetailMode(),
                PrimeConfigCodec::parseSurfaceDetailMode,
                "surface-detail mode");
        int voxelStrength = reader.value(
                VOXEL_TEXTURE_SURFACE_STRENGTH_KEY,
                defaultSettings.voxelTextureSurfaceStrengthSteps(),
                PrimeConfigCodec::parseVoxelSurfaceStrengthSteps,
                "voxel-surface strength");
        PostProcessingMode mode = reader.value(
                MODE_KEY,
                defaultSettings.postProcessingMode(),
                PrimeConfigCodec::parsePersistentMode,
                "post-processing mode");
        ReconstructionQualityMode quality = reader.value(
                QUALITY_KEY,
                defaultSettings.reconstructionQuality(),
                PrimeConfigCodec::parseQuality,
                "reconstruction quality");
        int latitude = reader.value(
                LATITUDE_DEGREES_KEY,
                defaultSettings.astronomy().latitudeDegrees(),
                PrimeConfigCodec::parseLatitudeDegrees,
                "observer latitude");
        int longitude = reader.value(
                SOLAR_LONGITUDE_DEGREES_KEY,
                defaultSettings.astronomy().solarLongitudeDegrees(),
                PrimeConfigCodec::parseSolarLongitudeDegrees,
                "solar longitude");
        int sun = reader.value(
                SUN_EV_KEY,
                defaultSettings.sunQuarterSteps(),
                PrimeConfigCodec::parseEvQuarterSteps,
                "sun exposure");
        int stars = reader.value(
                STAR_EV_KEY,
                defaultSettings.starQuarterSteps(),
                PrimeConfigCodec::parseStarEvQuarterSteps,
                "star exposure");
        int blockLights = reader.value(
                BLOCK_LIGHT_EV_KEY,
                defaultSettings.blockLightQuarterSteps(),
                PrimeConfigCodec::parseEvQuarterSteps,
                "block-light exposure");
        TransparentNeeMode transparentNeeMode = reader.value(
                TRANSPARENT_NEE_MODE_KEY,
                defaultSettings.transparentNeeMode(),
                PrimeConfigCodec::parseTransparentNeeMode,
                "transparent NEE mode");
        int finalExposure = reader.value(
                FINAL_EXPOSURE_EV_KEY,
                defaultSettings.finalExposureQuarterSteps(),
                PrimeConfigCodec::parseFinalExposureQuarterSteps,
                "final exposure");
        boolean hdr = reader.value(
                HDR_ENABLED_KEY,
                defaults.hdrEnabled(),
                PrimeConfigCodec::parseBoolean,
                "HDR switch");
        int referenceWhite = reader.value(
                REFERENCE_WHITE_NITS_KEY,
                defaults.referenceWhiteNits(),
                PrimeConfigCodec::parseReferenceWhiteNits,
                "HDR reference white");
        int exposureCompensation = reader.value(
                AUTO_EXPOSURE_COMPENSATION_KEY,
                defaultSettings.autoExposureCompensationSteps(),
                PrimeConfigCodec::parseAutoExposureCompensationSteps,
                "auto-exposure compensation");
        int roughness = reader.value(
                DEFAULT_ROUGHNESS_KEY,
                defaultSettings.defaultRoughnessSteps(),
                PrimeConfigCodec::parseRoughnessSteps,
                "default material roughness");
        boolean seamlessGlass = reader.value(
                SEAMLESS_GLASS_KEY,
                defaultSettings.seamlessGlass(),
                PrimeConfigCodec::parseBoolean,
                "seamless-glass switch");
        boolean airGap = reader.value(
                AIR_GAP_KEY,
                defaultSettings.airGap(),
                PrimeConfigCodec::parseBoolean,
                "air-gap switch");
        boolean vanillaPbrPresets = reader.value(
                VANILLA_PBR_PRESETS_KEY,
                defaultSettings.vanillaPbrPresets(),
                PrimeConfigCodec::parseBoolean,
                "vanilla-PBR preset switch");
        ReflexMode reflexMode = reader.value(
                REFLEX_MODE_KEY,
                defaults.reflexMode(),
                PrimeConfigCodec::parseReflexMode,
                "Reflex mode");
        boolean dlssFrameGenerationEnabled = reader.value(
                DLSS_FRAME_GENERATION_ENABLED_KEY,
                defaults.dlssFrameGenerationEnabled(),
                PrimeConfigCodec::parseBoolean,
                "DLSS frame-generation switch");
        int dlssFrameGenerationMultiplier = reader.value(
                DLSS_FRAME_GENERATION_MULTIPLIER_KEY,
                defaults.dlssFrameGenerationMultiplier(),
                PrimeConfigCodec::parseDlssFrameGenerationMultiplier,
                "DLSS frame-generation multiplier");
        boolean dlssFrameGenerationUiRecomposition = reader.value(
                DLSS_FRAME_GENERATION_UI_RECOMPOSITION_KEY,
                defaults.dlssFrameGenerationUiRecomposition(),
                PrimeConfigCodec::parseBoolean,
                "DLSS frame-generation UI recomposition switch");

        PrimeSettings settings = new PrimeSettings(
                pathTracing,
                surfaceDetail,
                voxelStrength,
                mode,
                quality,
                new AstronomySettings(latitude, longitude),
                new LightingSettings.Snapshot(
                        sun, stars, blockLights, transparentNeeMode, 0L),
                new DisplaySettings.Snapshot(finalExposure, exposureCompensation),
                new MaterialSettings.Snapshot(
                        roughness,
                        seamlessGlass,
                        airGap,
                        vanillaPbrPresets,
                        0L));
        reader.rewriteNeeded |= !properties.stringPropertyNames().equals(CURRENT_KEYS);
        return new DecodeResult(
                new PrimeConfigData(
                        settings,
                        additionalSpecularBounces,
                        minimumBounces,
                        maximumBounces,
                        terrainWorkers,
                        hdr,
                        referenceWhite,
                        reflexMode,
                        dlssFrameGenerationEnabled,
                        dlssFrameGenerationMultiplier,
                        dlssFrameGenerationUiRecomposition),
                reader.rewriteNeeded);
    }

    static String encode(PrimeConfigData data) {
        PrimeSettings settings = data.settings();
        return PATH_TRACING_ENABLED_KEY + "=" + settings.pathTracingEnabled() + "\n"
                + ADDITIONAL_SPECULAR_BOUNCES_KEY + "="
                + data.additionalSpecularBounces() + "\n"
                + MINIMUM_BOUNCES_KEY + "=" + data.minimumBounces() + "\n"
                + MAXIMUM_BOUNCES_KEY + "=" + data.maximumBounces() + "\n"
                + TERRAIN_WORKER_PERCENTAGE_KEY + "="
                + data.terrainWorkerPercentage() + "\n"
                + SURFACE_DETAIL_MODE_KEY + "="
                + settings.surfaceDetailMode().id() + "\n"
                + VOXEL_TEXTURE_SURFACE_STRENGTH_KEY + "="
                + formatVoxelSurfaceStrength(settings.voxelTextureSurfaceStrengthSteps()) + "\n"
                + MODE_KEY + "=" + settings.postProcessingMode().id() + "\n"
                + QUALITY_KEY + "=" + settings.reconstructionQuality().id() + "\n"
                + LATITUDE_DEGREES_KEY + "=" + settings.astronomy().latitudeDegrees() + "\n"
                + SOLAR_LONGITUDE_DEGREES_KEY + "="
                + settings.astronomy().solarLongitudeDegrees() + "\n"
                + SUN_EV_KEY + "=" + formatEv(settings.sunQuarterSteps()) + "\n"
                + STAR_EV_KEY + "=" + formatStarEv(settings.starQuarterSteps()) + "\n"
                + BLOCK_LIGHT_EV_KEY + "=" + formatEv(settings.blockLightQuarterSteps()) + "\n"
                + TRANSPARENT_NEE_MODE_KEY + "=" + settings.transparentNeeMode().id() + "\n"
                + FINAL_EXPOSURE_EV_KEY + "="
                + formatFinalExposure(settings.finalExposureQuarterSteps()) + "\n"
                + HDR_ENABLED_KEY + "=" + data.hdrEnabled() + "\n"
                + REFERENCE_WHITE_NITS_KEY + "=" + data.referenceWhiteNits() + "\n"
                + AUTO_EXPOSURE_COMPENSATION_KEY + "="
                + formatAutoExposureCompensation(settings.autoExposureCompensationSteps()) + "\n"
                + DEFAULT_ROUGHNESS_KEY + "="
                + formatRoughness(settings.defaultRoughnessSteps()) + "\n"
                + SEAMLESS_GLASS_KEY + "=" + settings.seamlessGlass() + "\n"
                + AIR_GAP_KEY + "=" + settings.airGap() + "\n"
                + VANILLA_PBR_PRESETS_KEY + "=" + settings.vanillaPbrPresets() + "\n"
                + REFLEX_MODE_KEY + "=" + data.reflexMode().name().toLowerCase(Locale.ROOT) + "\n"
                + DLSS_FRAME_GENERATION_ENABLED_KEY + "=" + data.dlssFrameGenerationEnabled() + "\n"
                + DLSS_FRAME_GENERATION_MULTIPLIER_KEY + "=" + data.dlssFrameGenerationMultiplier() + "\n"
                + DLSS_FRAME_GENERATION_UI_RECOMPOSITION_KEY + "="
                + data.dlssFrameGenerationUiRecomposition() + "\n";
    }

    static void log(PrimeConfigData data) {
        PrimeSettings settings = data.settings();
        PrimeInfo.LOGGER.info(
                "Prime settings: path tracing {}, additional specular bounces {}, minimum bounces {}, maximum bounces {}, terrain workers {}%, surface detail {} at {}x displacement height, post-processing {} quality {} (NRD-FSR {}x), latitude {} degrees, solar longitude {} degrees, sun {} EV, stars {} EV, block lights {} EV, transparent NEE {}, final exposure {} EV, HDR {}, reference white {}, auto-exposure compensation {}, default roughness {}, seamless glass {}, air gap {}, vanilla PBR presets {}, Reflex {}, DLSS frame generation {}, multiplier {}x, UI recomposition {}",
                settings.pathTracingEnabled() ? "enabled" : "disabled",
                data.additionalSpecularBounces(),
                data.minimumBounces(),
                data.maximumBounces(),
                data.terrainWorkerPercentage(),
                settings.surfaceDetailMode().id(),
                formatVoxelSurfaceStrength(settings.voxelTextureSurfaceStrengthSteps()),
                settings.postProcessingMode().id(),
                settings.reconstructionQuality().id(),
                settings.reconstructionQuality().upscaleRatio(),
                settings.astronomy().latitudeDegrees(),
                settings.astronomy().solarLongitudeDegrees(),
                formatEv(settings.sunQuarterSteps()),
                formatStarEv(settings.starQuarterSteps()),
                formatEv(settings.blockLightQuarterSteps()),
                settings.transparentNeeMode().id(),
                formatFinalExposure(settings.finalExposureQuarterSteps()),
                data.hdrEnabled() ? "enabled" : "disabled",
                data.referenceWhiteNits() == HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS
                        ? "automatic"
                        : data.referenceWhiteNits() + " nits",
                formatAutoExposureCompensation(settings.autoExposureCompensationSteps()),
                formatRoughness(settings.defaultRoughnessSteps()),
                settings.seamlessGlass() ? "enabled" : "disabled",
                settings.airGap() ? "enabled" : "disabled",
                settings.vanillaPbrPresets() ? "enabled" : "disabled",
                data.reflexMode().name().toLowerCase(Locale.ROOT),
                data.dlssFrameGenerationEnabled() ? "enabled" : "disabled",
                data.dlssFrameGenerationMultiplier(),
                data.dlssFrameGenerationUiRecomposition() ? "enabled" : "disabled");
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
        try {
            int multiplier = Integer.parseInt(value);
            if (multiplier < 2) {
                throw new IllegalArgumentException(
                        "DLSS frame-generation multiplier must be at least 2");
            }
            return multiplier;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "DLSS frame-generation multiplier must be an integer", exception);
        }
    }

    static int parseMaximumBounces(String value) {
        try {
            return MaximumBounceSettings.validateCount(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Maximum bounce count must be an integer", exception);
        }
    }

    static int parseAdditionalSpecularBounces(String value) {
        try {
            return SpecularBounceSettings.validateCount(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Additional specular bounce count must be an integer", exception);
        }
    }

    static int parseMinimumBounces(String value) {
        try {
            return MinimumBounceSettings.validateCount(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Minimum bounce count must be an integer", exception);
        }
    }

    static int parseTerrainWorkerPercentage(String value) {
        try {
            return TerrainWorkerSettings.validatePercentage(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Terrain worker percentage must be an integer", exception);
        }
    }

    static int parseLatitudeDegrees(String value) {
        try {
            return new AstronomySettings(
                    Integer.parseInt(value),
                    AstronomySettings.DEFAULT_SOLAR_LONGITUDE_DEGREES)
                    .latitudeDegrees();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Observer latitude must be an integer degree", exception);
        }
    }

    static int parseSolarLongitudeDegrees(String value) {
        try {
            return new AstronomySettings(
                    AstronomySettings.DEFAULT_LATITUDE_DEGREES,
                    Integer.parseInt(value))
                    .solarLongitudeDegrees();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Solar longitude must be an integer degree", exception);
        }
    }

    static AstronomyLoad parseAstronomy(Properties properties) {
        int latitudeDegrees = AstronomySettings.DEFAULT_LATITUDE_DEGREES;
        int solarLongitudeDegrees = AstronomySettings.DEFAULT_SOLAR_LONGITUDE_DEGREES;
        boolean rewriteNeeded = false;
        String latitude = properties.getProperty(LATITUDE_DEGREES_KEY);
        if (latitude != null) {
            try {
                latitudeDegrees = parseLatitudeDegrees(latitude);
            } catch (IllegalArgumentException exception) {
                PrimeInfo.LOGGER.warn(
                        "Invalid Prime observer latitude '{}'; using {} degrees north",
                        latitude,
                        AstronomySettings.DEFAULT_LATITUDE_DEGREES);
                rewriteNeeded = true;
            }
        } else {
            rewriteNeeded = true;
        }
        String solarLongitude = properties.getProperty(SOLAR_LONGITUDE_DEGREES_KEY);
        if (solarLongitude != null) {
            try {
                solarLongitudeDegrees = parseSolarLongitudeDegrees(solarLongitude);
            } catch (IllegalArgumentException exception) {
                PrimeInfo.LOGGER.warn(
                        "Invalid Prime solar longitude '{}'; using the March equinox",
                        solarLongitude);
                rewriteNeeded = true;
            }
        } else {
            rewriteNeeded = true;
        }
        return new AstronomyLoad(
                new AstronomySettings(latitudeDegrees, solarLongitudeDegrees),
                rewriteNeeded);
    }

    static int parseEvQuarterSteps(String value) {
        try {
            int quarterSteps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(LightingSettings.QUARTER_STEPS_PER_EV))
                    .intValueExact();
            LightingSettings.linearMultiplier(quarterSteps);
            return quarterSteps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("EV must be an exact 0.25-EV step", exception);
        }
    }

    static int parseVoxelSurfaceStrengthSteps(String value) {
        try {
            int steps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(VoxelSurfaceSettings.STEPS_PER_UNIT))
                    .intValueExact();
            VoxelSurfaceSettings.maximumHeight(steps);
            return steps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Voxel-surface strength must be an exact 0.01 step", exception);
        }
    }

    static String formatVoxelSurfaceStrength(int steps) {
        VoxelSurfaceSettings.maximumHeight(steps);
        return BigDecimal.valueOf(steps)
                .divide(BigDecimal.valueOf(VoxelSurfaceSettings.STEPS_PER_UNIT))
                .toPlainString();
    }

    static String formatEv(int quarterSteps) {
        LightingSettings.linearMultiplier(quarterSteps);
        return BigDecimal.valueOf(quarterSteps)
                .divide(BigDecimal.valueOf(LightingSettings.QUARTER_STEPS_PER_EV))
                .toPlainString();
    }

    static int parseStarEvQuarterSteps(String value) {
        try {
            int quarterSteps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(LightingSettings.QUARTER_STEPS_PER_EV))
                    .intValueExact();
            LightingSettings.starLinearMultiplier(quarterSteps);
            return quarterSteps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Star EV must be an exact 0.25-EV step", exception);
        }
    }

    static String formatStarEv(int quarterSteps) {
        LightingSettings.starLinearMultiplier(quarterSteps);
        return BigDecimal.valueOf(quarterSteps)
                .divide(BigDecimal.valueOf(LightingSettings.QUARTER_STEPS_PER_EV))
                .toPlainString();
    }

    static int parseFinalExposureQuarterSteps(String value) {
        try {
            int quarterSteps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(DisplaySettings.QUARTER_STEPS_PER_EV))
                    .intValueExact();
            DisplaySettings.finalExposureMultiplier(quarterSteps);
            return quarterSteps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Final exposure must be an exact 0.25-EV step", exception);
        }
    }

    static String formatFinalExposure(int quarterSteps) {
        DisplaySettings.finalExposureMultiplier(quarterSteps);
        return BigDecimal.valueOf(quarterSteps)
                .divide(BigDecimal.valueOf(DisplaySettings.QUARTER_STEPS_PER_EV))
                .toPlainString();
    }

    static int parseAutoExposureCompensationSteps(String value) {
        try {
            int steps = parseHundredthSteps(value);
            DisplaySettings.autoExposureCompensation(steps);
            return steps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Auto-exposure compensation must be an exact 0.01 step", exception);
        }
    }

    static String formatAutoExposureCompensation(int steps) {
        DisplaySettings.autoExposureCompensation(steps);
        return formatHundredthSteps(steps);
    }

    static int parseReferenceWhiteNits(String value) {
        try {
            return HdrOutput.validateReferenceWhiteNits(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "HDR reference white must be an integer number of nits", exception);
        }
    }

    static int parseRoughnessSteps(String value) {
        try {
            int steps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(MaterialSettings.STEPS_PER_UNIT))
                    .intValueExact();
            MaterialSettings.linearRoughness(steps);
            return steps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Default material roughness must be an exact 0.01 step", exception);
        }
    }

    static String formatRoughness(int steps) {
        MaterialSettings.linearRoughness(steps);
        return BigDecimal.valueOf(steps)
                .divide(BigDecimal.valueOf(MaterialSettings.STEPS_PER_UNIT))
                .toPlainString();
    }

    private static int parseHundredthSteps(String value) {
        return new BigDecimal(value)
                .multiply(BigDecimal.valueOf(DisplaySettings.HUNDREDTH_STEPS_PER_UNIT))
                .intValueExact();
    }

    private static String formatHundredthSteps(int steps) {
        return BigDecimal.valueOf(steps)
                .divide(BigDecimal.valueOf(DisplaySettings.HUNDREDTH_STEPS_PER_UNIT))
                .toPlainString();
    }

    record DecodeResult(PrimeConfigData data, boolean rewriteNeeded) {
    }

    record AstronomyLoad(AstronomySettings settings, boolean rewriteNeeded) {
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
