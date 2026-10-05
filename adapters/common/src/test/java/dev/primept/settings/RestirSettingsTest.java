package dev.primept.settings;

import static org.junit.jupiter.api.Assertions.*;
import dev.primept.abi.PrimeAbi.PrimeSettings;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class RestirSettingsTest {
    @Test
    void actualNamedAbiAndPropertiesPreserveNonDefaultControlsAndUnsignedSeed() {
        var restir = RestirSettings.defaults();
        double[] values = {7, 0,       2,     4,     45,    1,     1,      0.375, 1,
                           3, 0.04125, 0.375, 0.625, 0.125, -0.25, 0.0625, 2,     1,
                           1, 0.625,   2.5,   0.375, 0.875, 24.5,  0,      0};
        for (var control : RestirSettings.Control.values())
            restir = restir.with(control, values[control.ordinal()]);
        var settings = RenderSettings.defaults()
                               .withRestir(restir.withSeed(0xffffffffL))
                               .withRestirSpatialOnly(true);
        String file = SettingsFile.encode(settings);
        var loaded = SettingsFile.decode(file);
        assertEquals("", loaded.resetReason());
        assertEquals(settings, loaded.settings());
        assertEquals(settings.hashCode(), loaded.settings().hashCode());
        assertFalse(file.contains("diagnostics.restir_spatial_only"));
        assertTrue(file.contains("restir_pt.spatial_only=true\n"));
        assertTrue(file.contains("restir_pt.seed=4294967295\n"));
        var wire =
                ByteBuffer.allocateDirect((int)PrimeSettings.SIZE).order(ByteOrder.nativeOrder());
        loaded.settings().write(wire, false, RenderSettings.View.OUTPUT);
        var s = MemorySegment.ofBuffer(wire.duplicate().clear());
        assertEquals(0xffffffff, PrimeSettings.seed(s));
        assertEquals(1, PrimeSettings.restir_spatial_only(s));
        assertEquals(7, PrimeSettings.restir_history_length(s));
        assertEquals(0, PrimeSettings.restir_spatial_reuse(s));
        assertEquals(2, PrimeSettings.restir_spatial_iterations(s));
        assertEquals(4, PrimeSettings.restir_spatial_neighbors(s));
        assertEquals(45, PrimeSettings.restir_pairing_radius(s));
        assertEquals(1, PrimeSettings.restir_stochastic_reprojection(s));
        assertEquals(1, PrimeSettings.restir_duplicate_map(s));
        assertEquals(0.375f, PrimeSettings.restir_duplication_power(s));
        assertEquals(1, PrimeSettings.restir_decoupled_shading(s));
        assertEquals(3, PrimeSettings.restir_initial_samples(s));
        assertEquals(0.04125f, PrimeSettings.restir_distance_threshold(s));
        assertEquals(0.375f, PrimeSettings.restir_distance_sigma(s));
        assertEquals(0.625f, PrimeSettings.restir_roughness_threshold(s));
        assertEquals(0.125f, PrimeSettings.restir_roughness_sigma(s));
        assertEquals(-0.25f, PrimeSettings.restir_normal_threshold(s));
        assertEquals(0.0625f, PrimeSettings.restir_depth_threshold(s));
        assertEquals(2, PrimeSettings.restir_debug_view(s));
        assertEquals(1, PrimeSettings.restir_rr_decorrelation(s));
        assertEquals(1, PrimeSettings.restir_rr_mode(s));
        assertEquals(0.625f, PrimeSettings.restir_rr_factor(s));
        assertEquals(2.5f, PrimeSettings.restir_rr_stagnancy_exponent(s));
        assertEquals(0.375f, PrimeSettings.restir_rr_ema(s));
        assertEquals(0.875f, PrimeSettings.restir_rr_firefly_strength(s));
        assertEquals(24.5f, PrimeSettings.restir_rr_multiply_bound(s));
        assertEquals(0, PrimeSettings.restir_rr_bias_reduction(s));
        assertEquals(0, PrimeSettings.restir_rr_firefly(s));
        // Unrelated controls and copies retain the new settings.
        assertEquals(settings.restir(), settings.withOpacityMicromap(false)
                                                .with(RenderSettings.Control.BOUNCES, 32)
                                                .restir());
        assertEquals(RestirSettings.DEFAULT_SEED, RestirSettings.defaults().seed());
    }

    @Test
    void boundariesRejectNonFiniteFloatsFractionalCountsAndMalformedRequiredValues() {
        var defaults = RestirSettings.defaults();
        for (var control : RestirSettings.Control.values()) {
            assertDoesNotThrow(() -> defaults.with(control, control.minimum));
            assertDoesNotThrow(() -> defaults.with(control, control.maximum));
            for (double value :
                 new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                               control.minimum - 1, control.maximum + 1})
                assertThrows(IllegalArgumentException.class, () -> defaults.with(control, value));
            if (control.kind != RestirSettings.Kind.FLOAT)
                assertThrows(IllegalArgumentException.class,
                             () -> defaults.with(control, control.minimum + 0.5));
        }
        assertThrows(IllegalArgumentException.class,
                     () -> defaults.with(RestirSettings.Control.PAIRING_RADIUS, 31));
        assertThrows(IllegalArgumentException.class, () -> defaults.withSeed(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.withSeed(0x100000000L));
        String valid = SettingsFile.encode(RenderSettings.defaults());
        for (String broken : new String[] {
                     valid.replace("restir_pt.distance_sigma=0.2", "restir_pt.distance_sigma=NaN"),
                     valid.replace("restir_pt.spatial_reuse=true", "restir_pt.spatial_reuse=1"),
                     valid.replace("restir_pt.pairing_radius=30", "restir_pt.pairing_radius=31"),
                     valid.replace("restir_pt.seed=324478056", "restir_pt.seed=4294967296"),
                     valid.replace("restir_pt.rr_mode=2", "restir_pt.rr_mode=3"),
                     valid.replace("restir_pt.rr_ema=0.2", "restir_pt.rr_ema=Infinity"),
                     valid.replace("restir_pt.rr_decorrelation=false",
                                   "restir_pt.rr_decorrelation=1"),
                     valid.replace("restir_pt.initial_samples=1\n", "")}) {
            var loaded = SettingsFile.decode(broken);
            assertFalse(loaded.resetReason().isEmpty());
            assertEquals(RenderSettings.defaults(), loaded.settings());
        }
    }

    @Test
    void versionElevenMigratesSpatialOnlyAndKeepsAllExistingValidControls() {
        for (boolean spatialOnly : new boolean[] {false, true}) {
            var expected = RenderSettings.defaults()
                                   .withRenderer(RenderSettings.Renderer.RESTIR_PT)
                                   .withRestirSpatialOnly(spatialOnly)
                                   .withIgnoreGlobalHistoryResets(true)
                                   .with(RenderSettings.Control.BOUNCES, 32)
                                   .with(RenderSettings.Control.EXPOSURE_EV, -8);
            String legacy = SettingsFile.encode(expected)
                                    .replace("version=12", "version=11")
                                    .replaceAll("(?m)^restir_pt\\.[^\\n]*\\n", "") +
                            "diagnostics.restir_spatial_only=" + spatialOnly + "\n";
            var loaded = SettingsFile.decode(legacy);
            assertEquals("", loaded.resetReason());
            assertEquals(expected, loaded.settings());
            assertEquals(RestirSettings.defaults(), loaded.settings().restir());
            assertEquals(expected,
                         SettingsFile.decode(SettingsFile.encode(loaded.settings())).settings());
        }
    }
}
