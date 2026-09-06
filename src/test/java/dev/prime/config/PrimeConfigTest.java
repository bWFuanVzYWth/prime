package dev.prime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.AstronomySettings;
import dev.prime.render.HdrOutput;
import dev.prime.render.BounceSettings;
import dev.prime.render.RendererSettings;
import dev.prime.render.RealtimeRenderMode;
import dev.prime.render.SurfaceDetailMode;
import dev.prime.render.TransparentNeeMode;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainWorkerSettings;
import java.io.StringReader;
import java.util.Map;
import java.util.Properties;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

final class PrimeConfigTest {
    @Test
    void currentPropertiesRoundTripThroughTheSchemaCodec() throws Exception {
        String encoded = PrimeConfig.serializedContents();
        Properties properties = new Properties();
        properties.load(new StringReader(encoded));

        PrimeConfigCodec.DecodeResult decoded = PrimeConfigCodec.decode(properties);

        assertFalse(decoded.rewriteNeeded());
        assertEquals(encoded, PrimeConfigCodec.encode(decoded.data()));
    }

    @Test
    void baseColorCompensationDefaultsOnAndPersistsAnExplicitOptOut() throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(PrimeConfig.serializedContents()));
        String key = "material.base_color_compensation";
        properties.remove(key);
        PrimeConfigCodec.DecodeResult missing = PrimeConfigCodec.decode(properties);
        assertTrue(missing.rewriteNeeded());
        assertTrue(missing.data().material.baseColorCompensation());
        properties.setProperty(key, "invalid");
        assertTrue(PrimeConfigCodec.decode(properties).data().material.baseColorCompensation());
        properties.setProperty(key, "false");
        PrimeConfigCodec.DecodeResult disabled = PrimeConfigCodec.decode(properties);
        assertFalse(disabled.rewriteNeeded());
        assertFalse(disabled.data().material.baseColorCompensation());
        Properties roundTrip = new Properties();
        roundTrip.load(new StringReader(PrimeConfigCodec.encode(disabled.data())));
        assertFalse(PrimeConfigCodec.decode(roundTrip).data().material.baseColorCompensation());
    }

    @Test
    void baseColorCompensationSwitchInvalidatesHistoryWithoutChangingOtherMaterialSettings() {
        var previous = PrimeConfig.rendererSettings().material();
        long revision = PrimeConfig.rendererSettings().revision();
        boolean replacement = !previous.baseColorCompensation();
        try {
            PrimeConfig.setBaseColorCompensation(replacement);
            var changed = PrimeConfig.rendererSettings().material();
            assertEquals(replacement, changed.baseColorCompensation());
            assertEquals(previous.roughnessSteps(), changed.roughnessSteps());
            assertEquals(previous.seamlessGlass(), changed.seamlessGlass());
            assertEquals(previous.airGap(), changed.airGap());
            assertEquals(previous.vanillaPbrPresets(), changed.vanillaPbrPresets());
            assertEquals(revision + 1, PrimeConfig.rendererSettings().revision());
            PrimeConfig.setBaseColorCompensation(replacement);
            assertEquals(revision + 1, PrimeConfig.rendererSettings().revision());
            PrimeConfig.setVanillaPbrPresets(!previous.vanillaPbrPresets());
            assertEquals(replacement,
                    PrimeConfig.rendererSettings().material().baseColorCompensation());
        } finally {
            PrimeConfig.setVanillaPbrPresets(previous.vanillaPbrPresets());
            PrimeConfig.setBaseColorCompensation(previous.baseColorCompensation());
        }
    }

    @Test
    void transparentNeeModeMigratesMissingAndInvalidValuesToTheDefault() throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(PrimeConfig.serializedContents()));
        properties.remove("lighting.transparent_nee_mode");

        PrimeConfigCodec.DecodeResult missing = PrimeConfigCodec.decode(properties);

        assertTrue(missing.rewriteNeeded());
        assertEquals(
                TransparentNeeMode.STRAIGHT_APPROXIMATION,
                missing.data().lighting.transparentNeeMode());
        assertTrue(PrimeConfigCodec.encode(missing.data()).contains(
                "lighting.transparent_nee_mode=straight_approximation\n"));

        properties.setProperty("lighting.transparent_nee_mode", "mnee");
        PrimeConfigCodec.DecodeResult invalid = PrimeConfigCodec.decode(properties);
        assertTrue(invalid.rewriteNeeded());
        assertEquals(
                TransparentNeeMode.STRAIGHT_APPROXIMATION,
                invalid.data().lighting.transparentNeeMode());
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseTransparentNeeMode("mnee"));
    }

    @Test
    void liveTransparentNeeModeChangeInvalidatesAccumulation() {
        TransparentNeeMode previous =
                PrimeConfig.rendererSettings().lighting().transparentNeeMode();
        long previousRevision = PrimeConfig.rendererSettings().revision();
        TransparentNeeMode replacement = previous == TransparentNeeMode.STRAIGHT_APPROXIMATION
                ? TransparentNeeMode.UNBIASED_BSDF_ONLY
                : TransparentNeeMode.STRAIGHT_APPROXIMATION;
        try {
            PrimeConfig.setTransparentNeeMode(replacement);

            assertEquals(
                    replacement,
                    PrimeConfig.rendererSettings().lighting().transparentNeeMode());
            assertEquals(previousRevision + 1L, PrimeConfig.rendererSettings().revision());
        } finally {
            PrimeConfig.setTransparentNeeMode(previous);
        }
    }

    @Test
    void unknownKeysAreRemovedByCanonicalEncoding() throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(PrimeConfig.serializedContents()));
        properties.setProperty("unknown.private_key", "999");

        PrimeConfigCodec.DecodeResult decoded = PrimeConfigCodec.decode(properties);

        assertTrue(decoded.rewriteNeeded());
        assertFalse(PrimeConfigCodec.encode(decoded.data()).contains("unknown.private_key"));
    }

    @Test
    void legacyBounceKeysMigrateWithoutChangingTheirValues() throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(PrimeConfig.serializedContents()));
        properties.remove("renderer.additional_specular_bounces");
        properties.remove("renderer.minimum_bounces");
        properties.remove("renderer.maximum_bounces");
        properties.setProperty("renderer.primary_chain_limit", "13");
        properties.setProperty("renderer.wavefront_prefix_rounds", "6");
        properties.setProperty("renderer.scatter_count", "21");

        PrimeConfigCodec.DecodeResult decoded = PrimeConfigCodec.decode(properties);
        String encoded = PrimeConfigCodec.encode(decoded.data());

        assertTrue(decoded.rewriteNeeded());
        assertEquals(13, decoded.data().additionalSpecularBounces);
        assertEquals(6, decoded.data().minimumBounces);
        assertEquals(21, decoded.data().maximumBounces);
        assertTrue(encoded.contains("renderer.additional_specular_bounces=13\n"));
        assertTrue(encoded.contains("renderer.minimum_bounces=6\n"));
        assertTrue(encoded.contains("renderer.maximum_bounces=21\n"));
        assertFalse(encoded.contains("renderer.primary_chain_limit="));
        assertFalse(encoded.contains("renderer.wavefront_prefix_rounds="));
        assertFalse(encoded.contains("renderer.scatter_count="));
    }

    @Test
    void retiredRadianceCacheKeyIsIgnoredAndRemoved() throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(PrimeConfig.serializedContents()));
        properties.setProperty("renderer.sharc", "not-a-boolean");

        PrimeConfigCodec.DecodeResult decoded = PrimeConfigCodec.decode(properties);

        assertTrue(decoded.rewriteNeeded());
        assertFalse(PrimeConfigCodec.encode(decoded.data()).contains("renderer.sharc="));
    }

    @Test
    void restoreDefaultsIncludesStandaloneSchedulingSettings() {
        PrimeConfig.setMaximumBounces(BounceSettings.MAXIMUM_COUNT);
        PrimeConfig.setAdditionalSpecularBounces(BounceSettings.MAXIMUM_COUNT);
        PrimeConfig.setMinimumBounces(BounceSettings.MAXIMUM_FIXED_COUNT);
        PrimeConfig.setTerrainWorkerPercentage(TerrainWorkerSettings.MAXIMUM_PERCENTAGE);
        PrimeConfig.setHdrEnabled(true);
        PrimeConfig.setReferenceWhiteNits(400);
        PrimeConfig.setDlssFrameGenerationUiRecomposition(false);
        long revision = PrimeConfig.rendererSettings().revision();

        PrimeConfig.restoreDefaults();

        assertEquals(revision + 1L, PrimeConfig.rendererSettings().revision());
        assertEquals(
                BounceSettings.DEFAULT_COUNT,
                PrimeConfig.rendererSettings().maximumBounces());
        assertEquals(
                BounceSettings.DEFAULT_COUNT,
                PrimeConfig.rendererSettings().additionalSpecularBounces());
        assertEquals(
                BounceSettings.DEFAULT_FIXED_COUNT,
                PrimeConfig.rendererSettings().minimumBounces());
        assertEquals(
                TerrainWorkerSettings.DEFAULT_PERCENTAGE,
                PrimeConfig.rendererSettings().terrainWorkerPercentage());
        assertFalse(PrimeConfig.hdrEnabled());
        assertFalse(HdrOutput.requested());
        assertEquals(0, PrimeConfig.referenceWhiteNits());
        assertEquals(0, HdrOutput.referenceWhiteNits());
        assertTrue(PrimeConfig.dlssFrameGenerationUiRecomposition());
    }

    @Test
    void terrainWorkerShareDoesNotInvalidateTemporalRendering() {
        int previousPercentage = PrimeConfig.rendererSettings().terrainWorkerPercentage();
        long previousRevision = PrimeConfig.rendererSettings().revision();
        int replacement = previousPercentage == TerrainWorkerSettings.MAXIMUM_PERCENTAGE
                ? TerrainWorkerSettings.DEFAULT_PERCENTAGE
                : TerrainWorkerSettings.MAXIMUM_PERCENTAGE;
        try {
            PrimeConfig.setTerrainWorkerPercentage(replacement);

            assertEquals(replacement, PrimeConfig.rendererSettings().terrainWorkerPercentage());
            assertEquals(previousRevision, PrimeConfig.rendererSettings().revision());
        } finally {
            PrimeConfig.setTerrainWorkerPercentage(previousPercentage);
        }
    }

    @Test
    void hdrSwitchDoesNotInvalidateTemporalRendering() {
        boolean previous = PrimeConfig.hdrEnabled();
        long previousRevision = PrimeConfig.rendererSettings().revision();
        try {
            PrimeConfig.setHdrEnabled(!previous);

            assertEquals(previousRevision, PrimeConfig.rendererSettings().revision());
            assertEquals(!previous, HdrOutput.requested());
        } finally {
            PrimeConfig.setHdrEnabled(previous);
        }
    }

    @Test
    void referenceWhiteDoesNotInvalidateTemporalRendering() {
        int previous = PrimeConfig.referenceWhiteNits();
        long previousRevision = PrimeConfig.rendererSettings().revision();
        int replacement = previous == 400 ? 200 : 400;
        try {
            PrimeConfig.setReferenceWhiteNits(replacement);

            assertEquals(previousRevision, PrimeConfig.rendererSettings().revision());
            assertEquals(replacement, HdrOutput.referenceWhiteNits());
        } finally {
            PrimeConfig.setReferenceWhiteNits(previous);
        }
    }

    @Test
    void persistedNumericSettingsUseExactRepresentableStepsAndRanges() {
        assertIntegerCodec(
                PrimeConfigCodec::parseLatitudeDegrees,
                Map.of("-90", -90, "30", 30, "90", 90),
                "-91", "30.5");
        assertIntegerCodec(
                PrimeConfigCodec::parseSolarLongitudeDegrees,
                Map.of("0", 0, "359", 359),
                "-1", "360");
        assertIntegerCodec(
                PrimeConfigCodec::parseReferenceWhiteNits,
                Map.of("0", 0, "400", 400, "10000", 10_000),
                "-1", "400.0", "10001");

        assertStepCodec(
                PrimeConfigCodec::parseEvQuarterSteps,
                PrimeConfigCodec::formatEv,
                Map.of("-8", -32, "0", 0, "1.25", 5, "8", 32),
                Map.of(0, "0", 5, "1.25"),
                "0.1", "8.25");
        assertStepCodec(
                PrimeConfigCodec::parseFinalExposureQuarterSteps,
                PrimeConfigCodec::formatFinalExposure,
                Map.of("-8", -32, "0", 0, "1.25", 5, "8", 32),
                Map.of(0, "0", 5, "1.25"),
                "0.1", "8.25");
        assertStepCodec(
                PrimeConfigCodec::parseAutoExposureCompensationSteps,
                PrimeConfigCodec::formatAutoExposureCompensation,
                Map.of("0", 0, "0.5", 50, "1", 100),
                Map.of(50, "0.5"),
                "0.505", "1.01");
        assertStepCodec(
                PrimeConfigCodec::parseRoughnessSteps,
                PrimeConfigCodec::formatRoughness,
                Map.of("0", 0, "0.8", 80, "1", 100),
                Map.of(80, "0.8"),
                "0.805", "1.01");
        assertStepCodec(
                PrimeConfigCodec::parseVoxelSurfaceStrengthSteps,
                PrimeConfigCodec::formatVoxelSurfaceStrength,
                Map.of("0", 0, "1", 100, "2", 200),
                Map.of(100, "1"),
                "1.005", "2.01");
    }

    @Test
    void missingAndUnknownPostProcessingValuesRequestRrByDefault() {
        assertEquals(PostProcessingMode.DLSS_RR, PostProcessingMode.DEFAULT);
        assertEquals(PostProcessingMode.DLSS_RR, PostProcessingMode.fromId(null));
        assertEquals(PostProcessingMode.DLSS_RR, PostProcessingMode.fromId("future_backend"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.setPostProcessingMode(PostProcessingMode.DISABLED));
        assertEquals(ReconstructionQualityMode.PERFORMANCE, ReconstructionQualityMode.DEFAULT);
        assertEquals(
                ReconstructionQualityMode.PERFORMANCE,
                ReconstructionQualityMode.fromId("future_quality"));
    }

    @Test
    void serializedContentsContainCurrentPersistentSettings() {
        String serialized = PrimeConfig.serializedContents();
        assertTrue(serialized.contains("renderer.path_tracing=true\n"));
        assertTrue(serialized.contains("renderer.realtime_mode=path_tracing\n"));
        assertFalse(serialized.contains("renderer.sharc="));
        assertTrue(serialized.contains("renderer.additional_specular_bounces=12\n"));
        assertTrue(serialized.contains("renderer.minimum_bounces=2\n"));
        assertTrue(serialized.contains("renderer.maximum_bounces=12\n"));
        assertFalse(serialized.contains("renderer.primary_chain_limit="));
        assertFalse(serialized.contains("renderer.wavefront_prefix_rounds="));
        assertFalse(serialized.contains("renderer.scatter_count="));
        assertTrue(serialized.contains("terrain.worker_percentage=50\n"));
        assertTrue(serialized.contains("material.surface_detail=normal\n"));
        assertTrue(serialized.contains("material.displacement_height=1\n"));
        PrimeConfigData defaults = PrimeConfigData.defaults();
        assertEquals(SurfaceDetailMode.RESOURCE_NORMAL, defaults.surfaceDetailMode);
        assertEquals(100, defaults.voxelTextureSurfaceStrengthSteps);
        assertTrue(serialized.contains("astronomy.latitude_degrees=30\n"));
        assertTrue(serialized.contains("astronomy.solar_longitude_degrees=0\n"));
        assertTrue(serialized.contains("lighting.star_ev=0\n"));
        assertTrue(serialized.contains(
                "lighting.transparent_nee_mode=straight_approximation\n"));
        assertTrue(serialized.contains("display.final_exposure_ev=0\n"));
        assertTrue(serialized.contains("display.hdr=false\n"));
        assertTrue(serialized.contains("display.auto_exposure_compensation=0.6\n"));
        assertTrue(serialized.contains("display.reference_white_nits=0\n"));
        assertTrue(serialized.contains("material.seamless_glass=true\n"));
        assertTrue(serialized.contains("material.air_gap=true\n"));
        assertTrue(serialized.contains("material.vanilla_pbr_presets=true\n"));
        assertTrue(serialized.contains("material.base_color_compensation=true\n"));
        assertTrue(serialized.contains(
                "streamline.dlss_frame_generation_ui_recomposition=true\n"));
        assertTrue(defaults.dlssFrameGenerationUiRecomposition);
        assertTrue(defaults.material.seamlessGlass());
        assertTrue(defaults.material.airGap());
        assertTrue(defaults.material.vanillaPbrPresets());
        assertTrue(defaults.material.baseColorCompensation());
    }

    @Test
    void realtimeRendererModeIsStrictAndInvalidatesTheRendererRevision() {
        assertEquals(
                RealtimeRenderMode.TEXTURED_PRIMARY_RAYS,
                PrimeConfigCodec.parseRealtimeRenderMode("textured_primary_rays"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseRealtimeRenderMode("future_renderer"));

        assertEquals(RealtimeRenderMode.LIGHTWEIGHT_PATH_TRACING,
                PrimeConfigCodec.parseRealtimeRenderMode("lightweight_path_tracing"));
        assertTrue(RealtimeRenderMode.LIGHTWEIGHT_PATH_TRACING.usesReconstruction());
        RealtimeRenderMode previous = PrimeConfig.rendererSettings().realtimeRenderMode();
        long previousRevision = PrimeConfig.rendererSettings().revision();
        RealtimeRenderMode replacement = previous == RealtimeRenderMode.PATH_TRACING
                ? RealtimeRenderMode.TEXTURED_PRIMARY_RAYS
                : RealtimeRenderMode.PATH_TRACING;
        try {
            PrimeConfig.setRealtimeRenderMode(replacement);

            assertEquals(replacement, PrimeConfig.rendererSettings().realtimeRenderMode());
            assertEquals(previousRevision + 1L, PrimeConfig.rendererSettings().revision());
        } finally {
            PrimeConfig.setRealtimeRenderMode(previous);
        }
    }

    @Test
    void persistedSurfaceDetailAcceptsOnlyTheThreeCurrentModes() {
        assertEquals(
                SurfaceDetailMode.NONE,
                PrimeConfigCodec.parseSurfaceDetailMode("none"));
        assertEquals(
                SurfaceDetailMode.RESOURCE_NORMAL,
                PrimeConfigCodec.parseSurfaceDetailMode("normal"));
        assertEquals(
                SurfaceDetailMode.GEOMETRIC_DISPLACEMENT,
                PrimeConfigCodec.parseSurfaceDetailMode("displacement"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseSurfaceDetailMode("true"));
    }

    @Test
    void persistedIntegerSettingsUseTheirRuntimeRanges() {
        assertIntegerCodec(
                PrimeConfigCodec::parseMaximumBounces,
                Map.of("1", 1, "64", 64),
                "0", "65", "12.0");
        assertIntegerCodec(
                PrimeConfigCodec::parseAdditionalSpecularBounces,
                Map.of("1", 1, "8", 8, "64", 64),
                "0", "65", "8.0");
        assertIntegerCodec(
                PrimeConfigCodec::parseMinimumBounces,
                Map.of("1", 1, "8", 8),
                "0", "9", "2.0");
        assertIntegerCodec(
                PrimeConfigCodec::parseTerrainWorkerPercentage,
                Map.of("1", 1, "50", 50, "100", 100),
                "0", "101", "50.0");
    }

    @Test
    void pathTracingSwitchAcceptsOnlyExplicitBooleans() {
        assertTrue(PrimeConfigCodec.parseBoolean("true"));
        assertTrue(PrimeConfigCodec.parseBoolean("TRUE"));
        assertFalse(PrimeConfigCodec.parseBoolean("false"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseBoolean("enabled"));
    }

    private static void assertStepCodec(
            ToIntFunction<String> parser,
            IntFunction<String> formatter,
            Map<String, Integer> parsed,
            Map<Integer, String> formatted,
            String... invalid) {
        assertIntegerCodec(parser, parsed, invalid);
        formatted.forEach((steps, encoded) -> assertEquals(encoded, formatter.apply(steps)));
    }

    private static void assertIntegerCodec(
            ToIntFunction<String> parser,
            Map<String, Integer> valid,
            String... invalid) {
        valid.forEach((encoded, expected) -> assertEquals(
                expected.intValue(), parser.applyAsInt(encoded), encoded));
        for (String encoded : invalid) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parser.applyAsInt(encoded),
                    encoded);
        }
    }

    @Test
    void lightingAndMaterialChangesInvalidateRendererSettings() {
        RendererSettings initial = PrimeConfig.rendererSettings();
        int latitude = initial.astronomy().latitudeDegrees() == 30 ? -30 : 30;
        int roughness = initial.material().roughnessSteps() == 37 ? 38 : 37;
        try {
            PrimeConfig.setLatitudeDegrees(latitude);
            RendererSettings relit = PrimeConfig.rendererSettings();
            assertEquals(initial.revision() + 1L, relit.revision());
            assertEquals(initial.material(), relit.material());

            PrimeConfig.setDefaultRoughnessSteps(roughness);
            RendererSettings rematerialed = PrimeConfig.rendererSettings();
            assertEquals(relit.revision() + 1L, rematerialed.revision());
            assertEquals(relit.lighting(), rematerialed.lighting());
        } finally {
            PrimeConfig.setLatitudeDegrees(initial.astronomy().latitudeDegrees());
            PrimeConfig.setDefaultRoughnessSteps(initial.material().roughnessSteps());
        }
    }
}
