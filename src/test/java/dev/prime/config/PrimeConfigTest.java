package dev.prime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.AstronomySettings;
import dev.prime.render.HdrOutput;
import dev.prime.render.MaximumBounceSettings;
import dev.prime.render.MinimumBounceSettings;
import dev.prime.render.SpecularBounceSettings;
import dev.prime.render.SurfaceDetailMode;
import dev.prime.render.TransparentNeeMode;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainWorkerSettings;
import java.io.StringReader;
import java.util.Properties;
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
    void transparentNeeModeMigratesMissingAndInvalidValuesToTheDefault() throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(PrimeConfig.serializedContents()));
        properties.remove("lighting.transparent_nee_mode");

        PrimeConfigCodec.DecodeResult missing = PrimeConfigCodec.decode(properties);

        assertTrue(missing.rewriteNeeded());
        assertEquals(
                TransparentNeeMode.STRAIGHT_APPROXIMATION,
                missing.data().settings().transparentNeeMode());
        assertTrue(PrimeConfigCodec.encode(missing.data()).contains(
                "lighting.transparent_nee_mode=straight_approximation\n"));

        properties.setProperty("lighting.transparent_nee_mode", "mnee");
        PrimeConfigCodec.DecodeResult invalid = PrimeConfigCodec.decode(properties);
        assertTrue(invalid.rewriteNeeded());
        assertEquals(
                TransparentNeeMode.STRAIGHT_APPROXIMATION,
                invalid.data().settings().transparentNeeMode());
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseTransparentNeeMode("mnee"));
    }

    @Test
    void transparentNeeModeChangeAdvancesOnlyTheLightingRevision() {
        PrimeSettings initial = PrimeSettings.defaults();

        PrimeSettings changed = initial.withTransparentNeeMode(
                TransparentNeeMode.UNBIASED_BSDF_ONLY);

        assertEquals(initial.lightingRevision() + 1L, changed.lightingRevision());
        assertEquals(
                TransparentNeeMode.UNBIASED_BSDF_ONLY,
                changed.transparentNeeMode());
        assertEquals(changed, changed.withTransparentNeeMode(
                TransparentNeeMode.UNBIASED_BSDF_ONLY));
    }

    @Test
    void liveTransparentNeeModeChangeInvalidatesAccumulation() {
        TransparentNeeMode previous = PrimeConfig.settings().transparentNeeMode();
        long previousRevision = PrimeConfig.rendererSettings().revision();
        TransparentNeeMode replacement = previous == TransparentNeeMode.STRAIGHT_APPROXIMATION
                ? TransparentNeeMode.UNBIASED_BSDF_ONLY
                : TransparentNeeMode.STRAIGHT_APPROXIMATION;
        try {
            PrimeConfig.setTransparentNeeMode(replacement);

            assertEquals(replacement, PrimeConfig.settings().transparentNeeMode());
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
        assertEquals(13, decoded.data().additionalSpecularBounces());
        assertEquals(6, decoded.data().minimumBounces());
        assertEquals(21, decoded.data().maximumBounces());
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
        PrimeConfig.setMaximumBounces(MaximumBounceSettings.MAXIMUM_COUNT);
        PrimeConfig.setAdditionalSpecularBounces(SpecularBounceSettings.MAXIMUM_COUNT);
        PrimeConfig.setMinimumBounces(MinimumBounceSettings.MAXIMUM_COUNT);
        PrimeConfig.setTerrainWorkerPercentage(TerrainWorkerSettings.MAXIMUM_PERCENTAGE);
        PrimeConfig.setHdrEnabled(true);
        PrimeConfig.setReferenceWhiteNits(400);
        PrimeConfig.setDlssFrameGenerationUiRecomposition(false);

        PrimeConfig.restoreDefaults();

        assertEquals(MaximumBounceSettings.DEFAULT_COUNT, PrimeConfig.maximumBounces());
        assertEquals(
                MaximumBounceSettings.DEFAULT_COUNT,
                PrimeConfig.rendererSettings().maximumBounces());
        assertEquals(SpecularBounceSettings.DEFAULT_COUNT, PrimeConfig.additionalSpecularBounces());
        assertEquals(
                SpecularBounceSettings.DEFAULT_COUNT,
                PrimeConfig.rendererSettings().additionalSpecularBounces());
        assertEquals(
                MinimumBounceSettings.DEFAULT_COUNT,
                PrimeConfig.minimumBounces());
        assertEquals(
                MinimumBounceSettings.DEFAULT_COUNT,
                PrimeConfig.rendererSettings().minimumBounces());
        assertEquals(
                TerrainWorkerSettings.DEFAULT_PERCENTAGE,
                PrimeConfig.terrainWorkerPercentage());
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
        int previousPercentage = PrimeConfig.terrainWorkerPercentage();
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
    void persistedAstronomyAcceptsOnlyIntegerDegreesInRange() {
        assertEquals(-90, PrimeConfigCodec.parseLatitudeDegrees("-90"));
        assertEquals(30, PrimeConfigCodec.parseLatitudeDegrees("30"));
        assertEquals(90, PrimeConfigCodec.parseLatitudeDegrees("90"));
        assertEquals(0, PrimeConfigCodec.parseSolarLongitudeDegrees("0"));
        assertEquals(359, PrimeConfigCodec.parseSolarLongitudeDegrees("359"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseLatitudeDegrees("-91"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseLatitudeDegrees("30.5"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseSolarLongitudeDegrees("-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseSolarLongitudeDegrees("360"));
    }

    @Test
    void missingOrInvalidAstronomyMigratesToPersistedDefaults() {
        PrimeConfigCodec.AstronomyLoad missing =
                PrimeConfigCodec.parseAstronomy(new Properties());
        assertEquals(AstronomySettings.defaults(), missing.settings());
        assertTrue(missing.rewriteNeeded());

        Properties valid = new Properties();
        valid.setProperty("astronomy.latitude_degrees", "-45");
        valid.setProperty("astronomy.solar_longitude_degrees", "271");
        PrimeConfigCodec.AstronomyLoad accepted =
                PrimeConfigCodec.parseAstronomy(valid);
        assertEquals(
                new AstronomySettings(-45, 271),
                accepted.settings());
        assertFalse(accepted.rewriteNeeded());

        valid.setProperty("astronomy.latitude_degrees", "91");
        valid.setProperty("astronomy.solar_longitude_degrees", "-1");
        PrimeConfigCodec.AstronomyLoad invalid =
                PrimeConfigCodec.parseAstronomy(valid);
        assertEquals(AstronomySettings.defaults(), invalid.settings());
        assertTrue(invalid.rewriteNeeded());
        assertTrue(
                PrimeConfig.serializedContents()
                        .contains("astronomy.latitude_degrees=30\n"));
        assertTrue(
                PrimeConfig.serializedContents()
                        .contains("astronomy.solar_longitude_degrees=0\n"));
    }

    @Test
    void persistedEvAcceptsOnlyExactQuarterStopsInRange() {
        assertEquals(5, PrimeConfigCodec.parseEvQuarterSteps("1.25"));
        assertEquals(-32, PrimeConfigCodec.parseEvQuarterSteps("-8"));
        assertEquals(32, PrimeConfigCodec.parseEvQuarterSteps("8"));
        assertEquals("1.25", PrimeConfigCodec.formatEv(5));
        assertEquals("0", PrimeConfigCodec.formatEv(0));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseEvQuarterSteps("0.1"));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseEvQuarterSteps("8.25"));
        assertEquals(32, PrimeConfigCodec.parseStarEvQuarterSteps("8"));
        assertEquals("8", PrimeConfigCodec.formatStarEv(32));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseStarEvQuarterSteps("8.25"));
    }

    @Test
    void persistedAutoExposureCompensationAcceptsOnlyExactHundredthsInRange() {
        assertEquals(0, PrimeConfigCodec.parseAutoExposureCompensationSteps("0"));
        assertEquals(50, PrimeConfigCodec.parseAutoExposureCompensationSteps("0.5"));
        assertEquals(100, PrimeConfigCodec.parseAutoExposureCompensationSteps("1"));
        assertEquals("0.5", PrimeConfigCodec.formatAutoExposureCompensation(50));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseAutoExposureCompensationSteps("0.505"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseAutoExposureCompensationSteps("1.01"));
    }

    @Test
    void persistedReferenceWhiteAcceptsAutomaticOrIntegerNits() {
        assertEquals(0, PrimeConfigCodec.parseReferenceWhiteNits("0"));
        assertEquals(400, PrimeConfigCodec.parseReferenceWhiteNits("400"));
        assertEquals(10_000, PrimeConfigCodec.parseReferenceWhiteNits("10000"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseReferenceWhiteNits("-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseReferenceWhiteNits("400.0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseReferenceWhiteNits("10001"));
    }

    @Test
    void persistedFinalExposureAcceptsOnlyExactQuarterStopsInRange() {
        assertEquals(5, PrimeConfigCodec.parseFinalExposureQuarterSteps("1.25"));
        assertEquals(-32, PrimeConfigCodec.parseFinalExposureQuarterSteps("-8"));
        assertEquals(32, PrimeConfigCodec.parseFinalExposureQuarterSteps("8"));
        assertEquals("1.25", PrimeConfigCodec.formatFinalExposure(5));
        assertEquals("0", PrimeConfigCodec.formatFinalExposure(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseFinalExposureQuarterSteps("0.1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseFinalExposureQuarterSteps("8.25"));
    }

    @Test
    void persistedDefaultRoughnessAcceptsOnlyExactHundredths() {
        assertEquals(0, PrimeConfigCodec.parseRoughnessSteps("0"));
        assertEquals(80, PrimeConfigCodec.parseRoughnessSteps("0.8"));
        assertEquals(100, PrimeConfigCodec.parseRoughnessSteps("1"));
        assertEquals("0.8", PrimeConfigCodec.formatRoughness(80));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseRoughnessSteps("0.805"));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseRoughnessSteps("1.01"));
    }

    @Test
    void persistedVoxelSurfaceStrengthAcceptsExactPercentSteps() {
        assertEquals(0, PrimeConfigCodec.parseVoxelSurfaceStrengthSteps("0"));
        assertEquals(100, PrimeConfigCodec.parseVoxelSurfaceStrengthSteps("1"));
        assertEquals(200, PrimeConfigCodec.parseVoxelSurfaceStrengthSteps("2"));
        assertEquals("1", PrimeConfigCodec.formatVoxelSurfaceStrength(100));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseVoxelSurfaceStrengthSteps("1.005"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseVoxelSurfaceStrengthSteps("2.01"));
    }

    @Test
    void missingAndUnknownPostProcessingValuesRequestRrByDefault() {
        assertEquals(PostProcessingMode.DLSS_RR, PostProcessingMode.DEFAULT);
        assertEquals(PostProcessingMode.DLSS_RR, PostProcessingMode.fromId(null));
        assertEquals(PostProcessingMode.DLSS_RR, PostProcessingMode.fromId("future_backend"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeSettings.defaults()
                        .withPostProcessingMode(PostProcessingMode.DISABLED));
        assertEquals(ReconstructionQualityMode.PERFORMANCE, ReconstructionQualityMode.DEFAULT);
        assertEquals(
                ReconstructionQualityMode.PERFORMANCE,
                ReconstructionQualityMode.fromId("future_quality"));
    }

    @Test
    void serializedContentsContainCurrentPersistentSettings() {
        String serialized = PrimeConfig.serializedContents();
        assertTrue(serialized.contains("renderer.path_tracing=true\n"));
        assertFalse(serialized.contains("renderer.sharc="));
        assertTrue(serialized.contains("renderer.additional_specular_bounces=16\n"));
        assertTrue(serialized.contains("renderer.minimum_bounces=2\n"));
        assertTrue(serialized.contains("renderer.maximum_bounces=16\n"));
        assertFalse(serialized.contains("renderer.primary_chain_limit="));
        assertFalse(serialized.contains("renderer.wavefront_prefix_rounds="));
        assertFalse(serialized.contains("renderer.scatter_count="));
        assertTrue(serialized.contains("terrain.worker_percentage=50\n"));
        assertTrue(serialized.contains("material.surface_detail=normal\n"));
        assertTrue(serialized.contains("material.displacement_height=1\n"));
        assertEquals(
                SurfaceDetailMode.RESOURCE_NORMAL,
                PrimeSettings.defaults().surfaceDetailMode());
        assertEquals(
                100,
                PrimeSettings.defaults().voxelTextureSurfaceStrengthSteps());
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
        assertTrue(serialized.contains(
                "streamline.dlss_frame_generation_ui_recomposition=true\n"));
        assertTrue(PrimeConfigData.defaults().dlssFrameGenerationUiRecomposition());
        assertTrue(PrimeSettings.defaults().seamlessGlass());
        assertTrue(PrimeSettings.defaults().airGap());
        assertTrue(PrimeSettings.defaults().vanillaPbrPresets());
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
    void maximumBouncesAcceptsOnlyTheSharedRuntimeRange() {
        assertEquals(1, PrimeConfigCodec.parseMaximumBounces("1"));
        assertEquals(64, PrimeConfigCodec.parseMaximumBounces("64"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseMaximumBounces("0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseMaximumBounces("65"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseMaximumBounces("12.0"));
    }

    @Test
    void additionalSpecularBouncesAcceptsOnlyItsRuntimeRange() {
        assertEquals(1, PrimeConfigCodec.parseAdditionalSpecularBounces("1"));
        assertEquals(8, PrimeConfigCodec.parseAdditionalSpecularBounces("8"));
        assertEquals(64, PrimeConfigCodec.parseAdditionalSpecularBounces("64"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseAdditionalSpecularBounces("0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseAdditionalSpecularBounces("65"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseAdditionalSpecularBounces("8.0"));
    }

    @Test
    void minimumBouncesAcceptOnlyTheFixedRuntimeRange() {
        assertEquals(1, PrimeConfigCodec.parseMinimumBounces("1"));
        assertEquals(8, PrimeConfigCodec.parseMinimumBounces("8"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseMinimumBounces("0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseMinimumBounces("9"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseMinimumBounces("2.0"));
    }

    @Test
    void terrainWorkerPercentageAcceptsOnlyIntegerPercentages() {
        assertEquals(1, PrimeConfigCodec.parseTerrainWorkerPercentage("1"));
        assertEquals(50, PrimeConfigCodec.parseTerrainWorkerPercentage("50"));
        assertEquals(100, PrimeConfigCodec.parseTerrainWorkerPercentage("100"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseTerrainWorkerPercentage("0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseTerrainWorkerPercentage("101"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfigCodec.parseTerrainWorkerPercentage("50.0"));
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
}
