package dev.prime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.RendererSettings;
import dev.prime.render.SurfaceDetailMode;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainWorkerSettings;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import org.junit.jupiter.api.Test;

final class RendererSettingsMappingTest {
    @Test
    void mapsEveryRendererFieldAndDerivedValueFromOnePrimeSnapshot() {
        PrimeSettings source = PrimeSettings.defaults()
                .withPathTracingEnabled(false)
                .withSurfaceDetailMode(SurfaceDetailMode.GEOMETRIC_DISPLACEMENT)
                .withVoxelTextureSurfaceStrengthSteps(173)
                .withPostProcessingMode(PostProcessingMode.NRD_FSR)
                .withReconstructionQuality(ReconstructionQualityMode.BALANCED)
                .withLatitudeDegrees(-30)
                .withSolarLongitudeDegrees(271)
                .withSunQuarterSteps(5)
                .withStarQuarterSteps(-6)
                .withBlockLightQuarterSteps(7)
                .withFinalExposureQuarterSteps(-3)
                .withAutoExposureCompensationSteps(61)
                .withDefaultRoughnessSteps(37)
                .withSeamlessGlass(false)
                .withAirGap(false);

        RendererSettings mapped = PrimeConfig.rendererSettings(source, 42L);

        assertFalse(mapped.pathTracingEnabled());
        assertEquals(SurfaceDetailMode.GEOMETRIC_DISPLACEMENT, mapped.surfaceDetailMode());
        assertFalse(mapped.usesResourceNormals());
        assertTrue(mapped.usesGeometryDisplacement());
        assertEquals(173, mapped.voxelTextureSurfaceStrengthSteps());
        assertEquals(PostProcessingMode.NRD_FSR, mapped.postProcessingMode());
        assertEquals(ReconstructionQualityMode.BALANCED, mapped.reconstructionQuality());
        assertEquals(new AstronomySettings(-30, 271), mapped.astronomy());
        assertEquals(source.lighting(), mapped.lighting());
        assertEquals(source.material(), mapped.material());
        assertEquals(source.display(), mapped.display());
        assertEquals(
                TerrainWorkerSettings.DEFAULT_PERCENTAGE,
                mapped.terrainWorkerPercentage());
        assertEquals(42L, mapped.revision());
        assertRawEquals(
                VoxelSurfaceSettings.maximumHeight(173),
                mapped.voxelTextureSurfaceMaximumHeight());
        assertRawEquals(
                LightingSettings.linearMultiplier(5),
                mapped.lighting().sunMultiplier());
        assertRawEquals(
                LightingSettings.starLinearMultiplier(-6),
                mapped.lighting().starMultiplier());
        assertRawEquals(
                LightingSettings.linearMultiplier(7),
                mapped.lighting().blockLightMultiplier());
        assertRawEquals(
                MaterialSettings.linearRoughness(37),
                mapped.material().linearRoughness());
        assertFalse(mapped.material().seamlessGlass());
        assertFalse(mapped.material().airGap());
        assertTrue(mapped.material().vanillaPbrPresets());
        assertRawEquals(
                DisplaySettings.finalExposureMultiplier(-3),
                mapped.display().finalExposureMultiplier());
        assertRawEquals(
                DisplaySettings.autoExposureCompensation(61),
                mapped.display().autoExposureCompensation());
    }

    @Test
    void defaultsAndRestorePreserveProductValuesAndAdvanceAffectedRevisions() {
        PrimeSettings defaults = PrimeSettings.defaults();
        RendererSettings mappedDefaults = PrimeConfig.rendererSettings(defaults, 0L);
        assertTrue(mappedDefaults.pathTracingEnabled());
        assertEquals(SurfaceDetailMode.RESOURCE_NORMAL, mappedDefaults.surfaceDetailMode());
        assertTrue(mappedDefaults.usesResourceNormals());
        assertFalse(mappedDefaults.usesGeometryDisplacement());
        assertEquals(PostProcessingMode.DEFAULT, mappedDefaults.postProcessingMode());
        assertEquals(ReconstructionQualityMode.DEFAULT, mappedDefaults.reconstructionQuality());
        assertEquals(AstronomySettings.defaults(), mappedDefaults.astronomy());
        assertTrue(mappedDefaults.material().seamlessGlass());
        assertTrue(mappedDefaults.material().airGap());
        assertTrue(mappedDefaults.material().vanillaPbrPresets());
        assertEquals(0L, mappedDefaults.revision());

        PrimeSettings changed = defaults
                .withPathTracingEnabled(false)
                .withSurfaceDetailMode(SurfaceDetailMode.GEOMETRIC_DISPLACEMENT)
                .withVoxelTextureSurfaceStrengthSteps(150)
                .withLatitudeDegrees(-45)
                .withSunQuarterSteps(4)
                .withDefaultRoughnessSteps(25)
                .withSeamlessGlass(false)
                .withAirGap(false)
                .withVanillaPbrPresets(false);
        PrimeSettings restored = PrimeConfig.restoredDefaults(changed);

        RendererSettings mapped = PrimeConfig.rendererSettings(restored, 9L);
        assertTrue(mapped.pathTracingEnabled());
        assertEquals(SurfaceDetailMode.RESOURCE_NORMAL, mapped.surfaceDetailMode());
        assertEquals(VoxelSurfaceSettings.DEFAULT_STEPS, mapped.voxelTextureSurfaceStrengthSteps());
        assertEquals(AstronomySettings.defaults(), mapped.astronomy());
        assertEquals(
                LightingSettings.DEFAULT_SUN_QUARTER_STEPS,
                mapped.lighting().sunQuarterSteps());
        assertEquals(
                MaterialSettings.DEFAULT_ROUGHNESS_STEPS,
                mapped.material().roughnessSteps());
        assertTrue(mapped.material().seamlessGlass());
        assertTrue(mapped.material().airGap());
        assertTrue(mapped.material().vanillaPbrPresets());
        assertTrue(restored.lightingRevision() > changed.lightingRevision());
        assertTrue(restored.materialRevision() > changed.materialRevision());
        assertEquals(9L, mapped.revision());
    }

    private static void assertRawEquals(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }
}
