package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.shader.ShaderAbi;
import org.junit.jupiter.api.Test;

final class IntegratorSettingsTest {
    @Test
    void sampleEpochUsesOnlySamplingState() {
        assertEquals(17, IntegratorSettings.packSampleEpoch(17));
        assertEquals(
                17 | ShaderAbi.PATH_HISTORY_VALID_MASK,
                IntegratorSettings.packSampleEpoch(17, true));
        assertEquals(
                17,
                IntegratorSettings.packSampleEpoch(17, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> IntegratorSettings.packSampleEpoch(-1));
    }

    @Test
    void transparentNeeModeUsesOnlyItsReservedPathBit() {
        AstronomySettings astronomy = AstronomySettings.defaults();
        int approximation = IntegratorSettings.packSampleControl(
                41,
                astronomy,
                true,
                true,
                true,
                TransparentNeeMode.STRAIGHT_APPROXIMATION);
        int unbiased = IntegratorSettings.packSampleControl(
                41,
                astronomy,
                true,
                true,
                true,
                TransparentNeeMode.UNBIASED_BSDF_ONLY);

        assertEquals(0, approximation & ShaderAbi.PATH_TRANSPARENT_NEE_UNBIASED_MASK);
        assertEquals(
                ShaderAbi.PATH_TRANSPARENT_NEE_UNBIASED_MASK,
                unbiased & ShaderAbi.PATH_TRANSPARENT_NEE_UNBIASED_MASK);
        assertEquals(
                ShaderAbi.PATH_TRANSPARENT_NEE_UNBIASED_MASK,
                approximation ^ unbiased);
    }

    @Test
    void pathControlKeepsCameraMediumSeparateFromJitterAndBounceFields() {
        AstronomySettings astronomy = new AstronomySettings(-73, 271);
        int dry = IntegratorSettings.packPathControl(
                128, 18, astronomy, false, TransparentGuideMode.REFLECTION_AND_TRANSMISSION);
        int submerged = IntegratorSettings.packPathControl(
                128, 18, astronomy, true, TransparentGuideMode.REFLECTION_AND_TRANSMISSION);
        assertEquals(
                128,
                dry & ShaderAbi.PATH_MAXIMUM_BOUNCES_MASK);
        assertEquals(
                -73,
                ((dry >>> ShaderAbi.PATH_LATITUDE_SHIFT)
                        & ShaderAbi.PATH_LATITUDE_MASK)
                        - ShaderAbi.PATH_LATITUDE_BIAS);
        assertEquals(18, (dry >>> 16) & ShaderAbi.PATH_JITTER_PHASE_MASK);
        assertEquals(0, dry & ShaderAbi.PATH_CAMERA_IN_WATER_MASK);
        assertEquals(ShaderAbi.PATH_CAMERA_IN_WATER_MASK,
                submerged & ShaderAbi.PATH_CAMERA_IN_WATER_MASK);
        int screenshot = IntegratorSettings.packPathControl(
                128, 0, astronomy, false, TransparentGuideMode.DISABLED);
        assertEquals(0, (screenshot >>> 16) & ShaderAbi.PATH_JITTER_PHASE_MASK);
        int dlss = IntegratorSettings.packPathControl(
                128, 18, astronomy, false, TransparentGuideMode.TRANSMISSION_ONLY);
        assertEquals(
                ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_NRD,
                dry >>> ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_SHIFT
                        & ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_MASK);
        assertEquals(
                ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR,
                dlss >>> ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_SHIFT
                        & ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_MASK);
        assertEquals(
                ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_DISABLED,
                screenshot >>> ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_SHIFT
                        & ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_MASK);
        assertThrows(IllegalArgumentException.class,
                () -> IntegratorSettings.packPathControl(
                        128, -1, astronomy, false,
                        TransparentGuideMode.REFLECTION_AND_TRANSMISSION));
        assertThrows(IllegalArgumentException.class,
                () -> IntegratorSettings.packPathControl(
                        128, 0x2000, astronomy, false,
                        TransparentGuideMode.REFLECTION_AND_TRANSMISSION));
        assertThrows(IllegalArgumentException.class,
                () -> IntegratorSettings.packPathControl(
                        129, 0, astronomy, false,
                        TransparentGuideMode.REFLECTION_AND_TRANSMISSION));
    }

    @Test
    void sampleControlKeepsSeasonSeparateFromSobolIdentity() {
        AstronomySettings astronomy = new AstronomySettings(30, 359);
        int packed = IntegratorSettings.packSampleControl(
                0xabcd, astronomy, false, false);
        assertEquals(0xabcd, packed & ShaderAbi.PATH_SAMPLE_INDEX_MASK);
        assertEquals(
                359,
                (packed >>> ShaderAbi.PATH_SOLAR_LONGITUDE_SHIFT)
                        & ShaderAbi.PATH_SOLAR_LONGITUDE_MASK);
        assertEquals(0, packed & ShaderAbi.PATH_SEAMLESS_GLASS_MASK);
        assertEquals(0, packed & ShaderAbi.PATH_AIR_GAP_MASK);
        assertEquals(
                ShaderAbi.PATH_VANILLA_PBR_PRESETS_MASK,
                packed & ShaderAbi.PATH_VANILLA_PBR_PRESETS_MASK);
        assertEquals(
                ShaderAbi.PATH_SEAMLESS_GLASS_MASK,
                IntegratorSettings.packSampleControl(0xabcd, astronomy, true, false)
                        & ShaderAbi.PATH_SEAMLESS_GLASS_MASK);
        assertEquals(
                ShaderAbi.PATH_AIR_GAP_MASK,
                IntegratorSettings.packSampleControl(0xabcd, astronomy, false, true)
                        & ShaderAbi.PATH_AIR_GAP_MASK);
        assertEquals(
                0,
                IntegratorSettings.packSampleControl(
                                0xabcd, astronomy, false, false, false)
                        & ShaderAbi.PATH_VANILLA_PBR_PRESETS_MASK);
        assertThrows(
                IllegalArgumentException.class,
                () -> IntegratorSettings.packSampleControl(
                        1 << 16, astronomy, false, false));
    }

    @Test
    void materialLightingControlStoresIndependentEvAndRoughnessFields() {
        int packed = IntegratorSettings.packMaterialLightingControl(
                -16, 32, 16, 73, false);
        int sun = ((packed >>> ShaderAbi.PATH_SUN_EV_QUARTER_SHIFT)
                & ShaderAbi.PATH_EV_QUARTER_MASK) - ShaderAbi.PATH_EV_QUARTER_BIAS;
        int stars = ((packed >>> ShaderAbi.PATH_STAR_EV_QUARTER_SHIFT)
                & ShaderAbi.PATH_STAR_EV_QUARTER_MASK) - ShaderAbi.PATH_STAR_EV_QUARTER_BIAS;
        int block = ((packed >>> ShaderAbi.PATH_BLOCK_LIGHT_EV_QUARTER_SHIFT)
                & ShaderAbi.PATH_EV_QUARTER_MASK) - ShaderAbi.PATH_EV_QUARTER_BIAS;
        assertEquals(-16, sun);
        assertEquals(32, stars);
        assertEquals(16, block);
        assertEquals(73, (packed >>> ShaderAbi.PATH_MATERIAL_ROUGHNESS_SHIFT)
                & ShaderAbi.PATH_MATERIAL_ROUGHNESS_MASK);
        assertEquals(0, packed & ShaderAbi.PATH_SH_INPUT_MASK);
        assertEquals(
                ShaderAbi.PATH_SH_INPUT_MASK,
                IntegratorSettings.packMaterialLightingControl(-16, 32, 16, 73, true)
                        & ShaderAbi.PATH_SH_INPUT_MASK);
        assertThrows(IllegalArgumentException.class,
                () -> IntegratorSettings.packMaterialLightingControl(
                        -129, 0, 0, 80, false));
        assertThrows(IllegalArgumentException.class,
                () -> IntegratorSettings.packMaterialLightingControl(
                        0, 33, 0, 80, false));
        assertThrows(IllegalArgumentException.class,
                () -> IntegratorSettings.packMaterialLightingControl(
                        0, 0, 128, 80, false));
        assertThrows(IllegalArgumentException.class,
                () -> IntegratorSettings.packMaterialLightingControl(
                        0, 0, 0, 101, false));
    }

    @Test
    void rouletteStartsAtSecondScatter() {
        assertEquals(1, IntegratorSettings.RUSSIAN_ROULETTE_START);
    }

    @Test
    void reciprocalMisWeightsFormACompletePartition() {
        float forward = IntegratorSettings.powerHeuristic(0.3F, 0.7F);
        float reverse = IntegratorSettings.powerHeuristic(0.7F, 0.3F);
        assertEquals(1.0F, forward + reverse, 1.0e-6F);
        assertEquals(0.5F,
                IntegratorSettings.powerHeuristic(Float.MAX_VALUE, Float.MAX_VALUE),
                0.0F);
        assertEquals(0.5F,
                IntegratorSettings.powerHeuristic(Float.MIN_VALUE, Float.MIN_VALUE),
                0.0F);
    }

    @Test
    void rouletteCompensationPreservesExpectedThroughput() {
        float throughput = 0.2F;
        float etaScale = 2.25F;
        float survival = IntegratorSettings.rouletteSurvival(throughput, etaScale);
        assertEquals(throughput, survival * (throughput / survival), 1.0e-6F);
        assertEquals((float) Math.sqrt(0.45F), survival, 1.0e-6F);
        assertEquals(
                (float) Math.sqrt(0.001F),
                IntegratorSettings.rouletteSurvival(0.001F, 1.0F));
        assertEquals(1.0F, IntegratorSettings.rouletteSurvival(10.0F, 1.0F));
    }

    @Test
    void onlineMeanMatchesBatchMean() {
        float mean = IntegratorSettings.updateMean(0.0F, 1.0F, 0);
        mean = IntegratorSettings.updateMean(mean, 2.0F, 1);
        mean = IntegratorSettings.updateMean(mean, 6.0F, 2);
        assertEquals(3.0F, mean, 1.0e-6F);
    }

    @Test
    void sobolStreamIsStableSeparatedByEffectAndStrictlyUnitRange() {
        float[] first = IntegratorSettings.sobolSample2D(17, 29, 3, 5, 7, 1, 0);
        assertArrayEquals(first, IntegratorSettings.sobolSample2D(17, 29, 3, 5, 7, 1, 0));
        assertNotEquals(first[0], IntegratorSettings.sobolSample2D(17, 29, 4, 5, 7, 1, 0)[0]);
        assertNotEquals(first[0], IntegratorSettings.sobolSample2D(17, 29, 3, 5, 7, 2, 0)[0]);
        for (int sampleIndex = 0; sampleIndex < 10_000; sampleIndex++) {
            float[] sample = IntegratorSettings.sobolSample2D(
                    17, 29, sampleIndex, 5, 7, 1, 0);
            assertTrue(sample[0] >= 0.0F && sample[0] < 1.0F);
            assertTrue(sample[1] >= 0.0F && sample[1] < 1.0F);
        }
    }

    @Test
    void sobolPrefixStratifiesBothAxes() {
        int[] xBins = new int[16];
        int[] yBins = new int[16];
        for (int sampleIndex = 0; sampleIndex < 256; sampleIndex++) {
            float[] sample = IntegratorSettings.sobolSample2D(
                    17, 29, sampleIndex, 5, 7, 1, 0);
            xBins[(int) (sample[0] * 16.0F)]++;
            yBins[(int) (sample[1] * 16.0F)]++;
        }
        for (int bin = 0; bin < 16; bin++) {
            assertEquals(16, xBins[bin]);
            assertEquals(16, yBins[bin]);
        }
    }

    @Test
    void diffusePdfIsDefinedOnlyOnTheVisibleHemisphere() {
        assertEquals(1.0F / (float) Math.PI, IntegratorSettings.diffusePdf(1.0F), 1.0E-7F);
        assertEquals(0.0F, IntegratorSettings.diffusePdf(0.0F));
        assertEquals(0.0F, IntegratorSettings.diffusePdf(-1.0F));
    }
}
