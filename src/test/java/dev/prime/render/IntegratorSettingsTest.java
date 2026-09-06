package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.shader.ShaderAbi;
import org.junit.jupiter.api.Test;

final class IntegratorSettingsTest {
    @Test
    void baseColorCompensationUsesOnlyItsOwnBit() {
        for (TransparentNeeMode nee : TransparentNeeMode.values()) {
            int on = IntegratorSettings.packSampleControl(
                    0xffff, new AstronomySettings(30, 359), true, true, true, true, nee);
            int off = IntegratorSettings.packSampleControl(
                    0xffff, new AstronomySettings(30, 359), true, true, true, false, nee);
            assertEquals(0x2000_0000, on ^ off);
            assertEquals(ShaderAbi.PATH_BASE_COLOR_COMPENSATION_MASK, on ^ off);
            assertEquals(0, off & ShaderAbi.PATH_BASE_COLOR_COMPENSATION_MASK);
            assertEquals(0xffff, on & ShaderAbi.PATH_SAMPLE_INDEX_MASK);
            assertEquals(0, on & 0xc000_0000);
        }
    }

    @Test
    void sampleEpochUsesOnlySamplingState() {
        assertEquals(17, IntegratorSettings.packSampleEpoch(17, false));
        assertEquals(
                17 | ShaderAbi.PATH_HISTORY_VALID_MASK,
                IntegratorSettings.packSampleEpoch(17, true));
        assertEquals(
                17,
                IntegratorSettings.packSampleEpoch(17, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> IntegratorSettings.packSampleEpoch(-1, false));
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
                true, TransparentNeeMode.STRAIGHT_APPROXIMATION);
        int unbiased = IntegratorSettings.packSampleControl(
                41,
                astronomy,
                true,
                true,
                true,
                true, TransparentNeeMode.UNBIASED_BSDF_ONLY);

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
                0xabcd,
                astronomy,
                false,
                false,
                MaterialSettings.DEFAULT_VANILLA_PBR_PRESETS,
                true, TransparentNeeMode.DEFAULT);
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
                IntegratorSettings.packSampleControl(
                                0xabcd, astronomy, true, false, true,
                                true, TransparentNeeMode.DEFAULT)
                        & ShaderAbi.PATH_SEAMLESS_GLASS_MASK);
        assertEquals(
                ShaderAbi.PATH_AIR_GAP_MASK,
                IntegratorSettings.packSampleControl(
                                0xabcd, astronomy, false, true, true,
                                true, TransparentNeeMode.DEFAULT)
                        & ShaderAbi.PATH_AIR_GAP_MASK);
        assertEquals(
                0,
                IntegratorSettings.packSampleControl(
                                0xabcd,
                                astronomy,
                                false,
                                false,
                                false,
                                true, TransparentNeeMode.DEFAULT)
                        & ShaderAbi.PATH_VANILLA_PBR_PRESETS_MASK);
        assertThrows(
                IllegalArgumentException.class,
                () -> IntegratorSettings.packSampleControl(
                        1 << 16,
                        astronomy,
                        false,
                        false,
                        true,
                        true, TransparentNeeMode.DEFAULT));
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

}
