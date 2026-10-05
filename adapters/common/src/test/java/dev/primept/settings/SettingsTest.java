package dev.primept.settings;

import static org.junit.jupiter.api.Assertions.*;
import dev.primept.capture.Packets;
import java.lang.foreign.MemorySegment;
import static dev.primept.abi.PrimeAbi.*;
import dev.primept.render.OfflineMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SettingsTest {
    private static String legacyFile(RenderSettings settings, int version) {
        String result = SettingsFile.encode(settings).replace("version=" + RenderSettings.VERSION,
                                                              "version=" + version);
        result = result.replaceAll("(?m)^restir_pt\\.[^\\n]*\\n", "");
        if (version == 11)
            result += "diagnostics.restir_spatial_only=" + settings.restirSpatialOnly() + "\n";
        if (version <= 10)
            result = result.replace(
                    "diagnostics.restir_spatial_only=" + settings.restirSpatialOnly() + "\n", "");
        if (version <= 9)
            result = result.replace("diagnostics.native_noisy_output=" +
                                            settings.nativeNoisyOutput(),
                                    "render.ray_reconstruction=" + !settings.nativeNoisyOutput());
        return version == 8 ? result.replace("diagnostics.ignore_global_history_resets=" +
                                                     settings.ignoreGlobalHistoryResets() + "\n",
                                             "")
                            : result;
    }
    @Test
    void vertexBudgetDefaultsAndExplicitLegacyValues(@TempDir Path dir) {
        assertEquals(12, RenderSettings.defaults().value(RenderSettings.Control.BOUNCES));
        assertEquals(12, SettingsFile.load(dir.resolve("absent.properties"))
                                 .settings()
                                 .value(RenderSettings.Control.BOUNCES));
        for (int budget = 1; budget <= 64; ++budget) {
            var settings = RenderSettings.defaults().with(RenderSettings.Control.BOUNCES, budget);
            assertEquals(settings, SettingsFile.decode(SettingsFile.encode(settings)).settings());
            var wire = settingsBuffer();
            settings.write(wire, false, RenderSettings.View.OUTPUT);
            assertEquals(budget, PrimeSettings.bounces(view(wire)));
        }
        var legacy = RenderSettings.defaults().with(RenderSettings.Control.BOUNCES, 4);
        assertEquals(4, SettingsFile.decode(SettingsFile.encode(legacy))
                                .settings()
                                .value(RenderSettings.Control.BOUNCES));
        var missing =
                SettingsFile.decode(SettingsFile.encode(legacy).replace("render.bounces=4\n", ""));
        assertEquals(RenderSettings.defaults(), missing.settings());
        assertFalse(missing.resetReason().isEmpty());
        assertThrows(IllegalArgumentException.class,
                     () -> legacy.with(RenderSettings.Control.BOUNCES, 65));
    }

    @Test
    void completeFileRoundTripAndReplacement(@TempDir Path dir) throws Exception {
        var settings = RenderSettings.defaults()
                               .withPathTracing(false)
                               .withOpacityMicromap(false)
                               .withNativeNoisyOutput(true)
                               .withRestirSpatialOnly(true)
                               .withDlssQuality(RenderSettings.DlssQuality.QUALITY)
                               .withLightSampling(RenderSettings.LightSampling.TREE);
        for (var control : RenderSettings.Control.values())
            settings = settings.with(control, control.maximum);
        Path file = dir.resolve("config/primept.properties");
        assertEquals(RenderSettings.defaults(), SettingsFile.load(file).settings());
        SettingsFile.save(file, settings);
        assertEquals(settings, SettingsFile.load(file).settings());
        assertEquals("", SettingsFile.load(file).resetReason());
        SettingsFile.save(file, RenderSettings.defaults());
        assertEquals(RenderSettings.defaults(), SettingsFile.load(file).settings());
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
    }
    @Test
    void rejectsWholeOldFutureMissingMalformedAndInvalidSettings() {
        var changed = RenderSettings.defaults().withPathTracing(false).with(
                RenderSettings.Control.BOUNCES, 12);
        String valid = SettingsFile.encode(changed);
        for (String broken : new String[] {
                     valid.replace("version=12", "version=0"),
                     valid.replace("version=12", "version=7"),
                     valid.replace("version=12", "version=13"),
                     valid.replace("version=12", ""),
                     valid.replace("render.bounces=12", ""),
                     valid.replace("render.bounces=12", "render.bounces=65"),
                     valid.replace("terrain.batches_per_frame=8", ""),
                     valid.replace("terrain.batches_per_frame=8", "terrain.batches_per_frame=0"),
                     valid.replace("terrain.batches_per_frame=8", "terrain.batches_per_frame=129"),
                     valid.replace("terrain.batches_per_frame=8", "terrain.batches_per_frame=NaN"),
                     valid.replace("renderer=vanilla", "renderer=unknown"),
                     valid.replace("renderer=vanilla\n", ""),
                     valid.replace("render.opacity_micromap=true", ""),
                     valid.replace("render.opacity_micromap=true", "render.opacity_micromap=maybe"),
                     valid.replace("diagnostics.native_noisy_output=false", ""),
                     valid.replace("diagnostics.native_noisy_output=false",
                                   "diagnostics.native_noisy_output=maybe"),
                     valid.replace("render.dlss_quality=PERFORMANCE", ""),
                     valid.replace("render.dlss_quality=PERFORMANCE",
                                   "render.dlss_quality=UNKNOWN"),
                     valid.replace("render.light_sampling=TREE", ""),
                     valid.replace("render.light_sampling=TREE", "render.light_sampling=UNKNOWN"),
                     valid.replace("render.light_sampling=TREE", "render.light_sampling=2"),
                     valid.replace("diagnostics.ignore_global_history_resets=false\n", ""),
                     valid.replace("diagnostics.ignore_global_history_resets=false",
                                   "diagnostics.ignore_global_history_resets=maybe"),
                     valid + "render.bounces=NaN\n",
                     valid + "bad=\\uXYZW\n"}) {
            var loaded = SettingsFile.decode(broken);
            assertEquals(RenderSettings.defaults(), loaded.settings());
            assertFalse(loaded.resetReason().isEmpty());
        }
        assertEquals(changed, SettingsFile.decode(valid + "unrelated=ignored\n").settings());
    }
    @Test
    void immutableControlsAndNamedCFields() {
        var original = RenderSettings.defaults();
        var changed = original.with(RenderSettings.Control.EXPOSURE_EV, 8)
                              .with(RenderSettings.Control.SUN_EV, -4);
        assertEquals(0, original.value(RenderSettings.Control.EXPOSURE_EV));
        assertThrows(IllegalArgumentException.class,
                     () -> original.with(RenderSettings.Control.BOUNCES, 0));
        var bytes = settingsBuffer();
        changed.write(bytes, true, RenderSettings.View.NORMAL);
        assertEquals(PrimeSettings.SIZE, bytes.position());
        assertEquals(PrimeSettings.SIZE,
                     PrimeHeader.struct_size(PrimeSettings.header(view(bytes))));
        assertEquals(PRIME_ABI_VERSION, PrimeHeader.abi_version(PrimeSettings.header(view(bytes))));
        assertEquals(1, PrimeSettings.mode(view(bytes)));
        assertEquals(12, PrimeSettings.bounces(view(bytes)));
        assertEquals(1, PrimeSettings.offline_samples(view(bytes)));
        assertEquals(4.0f, PrimeSettings.exposure(view(bytes)));
        assertEquals(.75f, PrimeSettings.hue(view(bytes)));
        assertEquals(.20f, PrimeSettings.saturation(view(bytes)));
        assertEquals(3, PrimeSettings.view(view(bytes)));
        assertEquals(.5f, PrimeSettings.sun(view(bytes)));
        assertEquals(1f, PrimeSettings.sky(view(bytes)));
        assertEquals(128f, PrimeSettings.depth_range(view(bytes)));
        assertEquals(0x13572468, PrimeSettings.seed(view(bytes)));
        assertEquals(30, PrimeSettings.latitude_degrees(view(bytes)));
        assertEquals(0, PrimeSettings.solar_longitude_degrees(view(bytes)));
        assertEquals(1, PrimeSettings.opacity_micromap(view(bytes)));
        assertEquals(0, PrimeSettings.native_noisy_output(view(bytes)));
        assertEquals(3, PrimeSettings.reconstruction_quality(view(bytes)));
        assertEquals(8, PrimeSettings.terrain_batches_per_frame(view(bytes)));
        assertEquals(1f, PrimeSettings.stars(view(bytes)));
        assertEquals(.6f, PrimeSettings.auto_exposure_compensation(view(bytes)));
        assertEquals(0, PrimeSettings.hdr(view(bytes)));
        assertEquals(0, PrimeSettings.hdr_reference_white(view(bytes)));
        assertEquals(0, PrimeSettings.frame_generation(view(bytes)));
        assertEquals(1, PrimeSettings.light_sampling(view(bytes)));
        assertEquals(0, PrimeSettings.integrator(view(bytes)));
        assertEquals(0, PrimeSettings.ignore_global_history_resets(view(bytes)));
        assertEquals(216, PrimeSettings.SIZE);
        assertEquals(0, PrimeSettings.restir_spatial_only(view(bytes)));
    }
    @Test
    void saturationDefaultsPreserveSavedValuesAndIndependentWire(@TempDir Path dir)
            throws Exception {
        var control = RenderSettings.Control.SATURATION;
        var defaults = RenderSettings.defaults();
        assertEquals(12, RenderSettings.VERSION);
        assertEquals(20, defaults.value(control));
        Path file = dir.resolve("primept.properties");
        assertEquals(20, SettingsFile.load(file).settings().value(control));
        var before = settingsBuffer();
        var after = settingsBuffer();
        defaults.write(before, false, RenderSettings.View.OUTPUT);
        for (int value : new int[] {0, 8, 20, 50}) {
            var saved = defaults.with(control, value);
            SettingsFile.save(file, saved);
            var loaded = SettingsFile.load(file);
            assertEquals("", loaded.resetReason());
            assertEquals(saved, loaded.settings());
            loaded.settings().write(after, false, RenderSettings.View.OUTPUT);
            assertEquals(value / 100f, PrimeSettings.saturation(view(after)));
            assertOnlyFieldChanged(before, after, 28);
        }
    }
    @Test
    void terrainBatchBudgetDefaultsRangePersistenceAndIndependentWire(@TempDir Path dir) {
        var control = RenderSettings.Control.TERRAIN_BATCHES_PER_FRAME;
        var defaults = RenderSettings.defaults();
        assertEquals(8, defaults.value(control));
        assertEquals(8,
                     SettingsFile.load(dir.resolve("absent.properties")).settings().value(control));
        var before = settingsBuffer();
        var after = settingsBuffer();
        for (int budget = 1; budget <= 128; ++budget) {
            var changed = defaults.with(control, budget);
            assertEquals(changed, SettingsFile.decode(SettingsFile.encode(changed)).settings());
            for (boolean offline : new boolean[] {false, true}) {
                defaults.write(before, offline, RenderSettings.View.OUTPUT);
                changed.write(after, offline, RenderSettings.View.OUTPUT);
                assertEquals(PrimeSettings.SIZE, after.position());
                assertEquals(budget, PrimeSettings.terrain_batches_per_frame(view(after)));
                assertOnlyFieldChanged(before, after, 72);
            }
        }
        assertNotEquals(defaults, defaults.with(control, 1));
        assertEquals(8, defaults.value(control));
        assertThrows(IllegalArgumentException.class, () -> defaults.with(control, 0));
        assertThrows(IllegalArgumentException.class, () -> defaults.with(control, 129));
    }
    @Test
    void matureDisplayControlsPreserveBoundsPersistenceAndIndependentAbiFields() {
        var defaults = RenderSettings.defaults();
        var controls = new RenderSettings.Control[] {
                RenderSettings.Control.STARS, RenderSettings.Control.AUTO_EXPOSURE,
                RenderSettings.Control.HDR, RenderSettings.Control.HDR_WHITE,
                RenderSettings.Control.FRAME_GENERATION};
        int[] offsets = {76, 80, 84, 88, 92};
        int[] expectedDefaults = {100, 60, 0, 0, 0};
        var before = settingsBuffer();
        var after = settingsBuffer();
        for (int i = 0; i < controls.length; i++) {
            var control = controls[i];
            assertEquals(expectedDefaults[i], defaults.value(control));
            assertThrows(IllegalArgumentException.class,
                         () -> defaults.with(control, control.minimum - 1));
            assertThrows(IllegalArgumentException.class,
                         () -> defaults.with(control, control.maximum + 1));
            for (int value : new int[] {control.minimum, control.maximum, control.initial}) {
                var changed = defaults.with(control, value);
                assertEquals(changed, SettingsFile.decode(SettingsFile.encode(changed)).settings());
                for (boolean offline : new boolean[] {false, true}) {
                    defaults.write(before, offline, RenderSettings.View.OUTPUT);
                    changed.write(after, offline, RenderSettings.View.OUTPUT);
                    assertOnlyFieldChanged(before, after, offsets[i]);
                    if (control == RenderSettings.Control.STARS)
                        assertEquals(value / 100f, PrimeSettings.stars(view(after)));
                    else if (control == RenderSettings.Control.AUTO_EXPOSURE)
                        assertEquals(value / 100f,
                                     PrimeSettings.auto_exposure_compensation(view(after)));
                    else if (control == RenderSettings.Control.HDR)
                        assertEquals(value, PrimeSettings.hdr(view(after)));
                    else if (control == RenderSettings.Control.HDR_WHITE)
                        assertEquals(value, PrimeSettings.hdr_reference_white(view(after)));
                    else
                        assertEquals(value, PrimeSettings.frame_generation(view(after)));
                }
            }
            String valid = SettingsFile.encode(defaults);
            for (String malformed :
                 new String[] {valid.replace(control.key + "=" + control.initial + "\n", ""),
                               valid.replace(control.key + "=" + control.initial,
                                             control.key + "=" + (control.maximum + 1)),
                               valid.replace(control.key + "=" + control.initial,
                                             control.key + "=NaN")}) {
                assertFalse(SettingsFile.decode(malformed).resetReason().isEmpty());
            }
        }
        assertFalse(defaults.hdr());
        assertFalse(defaults.frameGeneration());
        assertTrue(defaults.with(RenderSettings.Control.HDR, 1).hdr());
        assertTrue(defaults.with(RenderSettings.Control.FRAME_GENERATION, 1).frameGeneration());
    }

    private static void assertOnlyFieldChanged(ByteBuffer before, ByteBuffer after, long offset) {
        // Check both sides: appended fields must not weaken the original independent byte oracle.
        for (long[] slice :
             new long[][] {{0, offset}, {offset + 4, PrimeSettings.SIZE - offset - 4}})
            assertArrayEquals(view(before)
                                      .asSlice(slice[0], slice[1])
                                      .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                              view(after)
                                      .asSlice(slice[0], slice[1])
                                      .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
    }
    @Test
    void distinctRenderersPersistAndSurviveIndependentControlChanges() {
        var defaults = RenderSettings.defaults();
        assertEquals(RenderSettings.Renderer.PATH_TRACE, defaults.renderer());
        assertThrows(NullPointerException.class, () -> defaults.withRenderer(null));
        assertThrows(IllegalArgumentException.class,
                     () -> RenderSettings.Renderer.fromKey("restir"));
        var before = settingsBuffer();
        var after = settingsBuffer();
        for (var renderer : RenderSettings.Renderer.values()) {
            assertEquals(renderer, RenderSettings.Renderer.fromKey(renderer.key));
            var chosen = defaults.withRenderer(renderer);
            assertEquals(renderer != RenderSettings.Renderer.VANILLA, chosen.pathTracing());
            assertEquals(chosen, SettingsFile.decode(SettingsFile.encode(chosen)).settings());
            assertEquals(chosen.hashCode(),
                         SettingsFile.decode(SettingsFile.encode(chosen)).settings().hashCode());
            var changed = chosen.with(RenderSettings.Control.BOUNCES, 8)
                                  .withOpacityMicromap(false)
                                  .withNativeNoisyOutput(true)
                                  .withDlssQuality(RenderSettings.DlssQuality.QUALITY)
                                  .withLightSampling(RenderSettings.LightSampling.TREE);
            assertEquals(renderer, changed.renderer());
            for (boolean offline : new boolean[] {false, true}) {
                defaults.write(before, offline, RenderSettings.View.OUTPUT);
                chosen.write(after, offline, RenderSettings.View.OUTPUT);
                assertEquals(renderer == RenderSettings.Renderer.RESTIR_PT ? 1 : 0,
                             PrimeSettings.integrator(view(after)));
                assertOnlyFieldChanged(before, after, 100);
            }
        }
        var restir = defaults.withRenderer(RenderSettings.Renderer.RESTIR_PT);
        assertSame(restir, restir.withPathTracing(true));
        assertEquals(RenderSettings.Renderer.VANILLA, restir.withPathTracing(false).renderer());
        assertEquals(RenderSettings.Renderer.PATH_TRACE,
                     restir.withPathTracing(false).withPathTracing(true).renderer());
    }
    @Test
    void opacityMicromapIsEnabledByDefaultAndCanBePersistedAndToggled() {
        var defaults = RenderSettings.defaults();
        assertTrue(defaults.opacityMicromap());
        var disabled = defaults.withOpacityMicromap(false);
        assertTrue(defaults.opacityMicromap());
        assertNotEquals(defaults, disabled);
        assertEquals(disabled, SettingsFile.decode(SettingsFile.encode(disabled)).settings());
        assertFalse(disabled.withPathTracing(false).opacityMicromap());
        assertFalse(disabled.with(RenderSettings.Control.BOUNCES, 8).opacityMicromap());
        var bytes = settingsBuffer();
        disabled.write(bytes, true, RenderSettings.View.OUTPUT);
        assertEquals(0, PrimeSettings.opacity_micromap(view(bytes)));
    }
    @Test
    void nativeNoisyOutputAndQualityRoundTripPreserveIndependentSettings() {
        var defaults = RenderSettings.defaults();
        assertFalse(defaults.nativeNoisyOutput());
        assertEquals(RenderSettings.DlssQuality.PERFORMANCE, defaults.dlssQuality());
        var noisy = defaults.withNativeNoisyOutput(true);
        assertNotEquals(defaults, noisy);
        assertTrue(noisy.withPathTracing(false)
                           .withOpacityMicromap(false)
                           .with(RenderSettings.Control.BOUNCES, 8)
                           .nativeNoisyOutput());
        var bytes = settingsBuffer();
        for (var quality : RenderSettings.DlssQuality.values()) {
            var changed = noisy.withDlssQuality(quality);
            assertTrue(changed.nativeNoisyOutput());
            assertEquals(changed, SettingsFile.decode(SettingsFile.encode(changed)).settings());
            changed.write(bytes, false, RenderSettings.View.OUTPUT);
            assertEquals(1, PrimeSettings.native_noisy_output(view(bytes)));
            assertEquals(quality.ordinal(), PrimeSettings.reconstruction_quality(view(bytes)));
            assertEquals(quality, changed.withNativeNoisyOutput(false).dlssQuality());
        }
        assertFalse(defaults.nativeNoisyOutput());
        assertThrows(NullPointerException.class, () -> defaults.withDlssQuality(null));
    }
    @Test
    void oldReconstructionBooleanMigratesWithOppositeNoisyOutputMeaning() {
        for (int version : new int[] {8, 9}) {
            for (boolean oldReconstruction : new boolean[] {false, true}) {
                var expected = RenderSettings.defaults()
                                       .withRenderer(RenderSettings.Renderer.RESTIR_PT)
                                       .withNativeNoisyOutput(!oldReconstruction)
                                       .withDlssQuality(RenderSettings.DlssQuality.BALANCED)
                                       .withLightSampling(RenderSettings.LightSampling.TREE)
                                       .withIgnoreGlobalHistoryResets(version == 9)
                                       .with(RenderSettings.Control.BOUNCES, 32)
                                       .with(RenderSettings.Control.EXPOSURE_EV, -8);
                String legacy = legacyFile(expected, version);
                assertTrue(
                        legacy.contains("render.ray_reconstruction=" + oldReconstruction + "\n"));
                var migrated = SettingsFile.decode(legacy);
                assertEquals("", migrated.resetReason());
                assertEquals(expected, migrated.settings());
                String canonical = SettingsFile.encode(migrated.settings());
                assertTrue(canonical.contains("version=12\n"));
                assertTrue(canonical.contains(
                        "diagnostics.native_noisy_output=" + !oldReconstruction + "\n"));
                assertFalse(canonical.contains("ray_reconstruction"));
                assertEquals(expected, SettingsFile.decode(canonical).settings());
                var before = settingsBuffer();
                var after = settingsBuffer();
                for (var diagnosticView : RenderSettings.View.values()) {
                    expected.write(before, false, diagnosticView);
                    expected.withNativeNoisyOutput(oldReconstruction)
                            .write(after, false, diagnosticView);
                    assertEquals(!oldReconstruction ? 1 : 0,
                                 PrimeSettings.native_noisy_output(view(before)));
                    assertEquals(diagnosticView.ordinal(), PrimeSettings.view(view(before)));
                    assertOnlyFieldChanged(before, after, 64);
                }
                for (String malformed : new String[] {
                             legacy.replace("render.ray_reconstruction=" + oldReconstruction + "\n",
                                            ""),
                             legacy.replace("render.ray_reconstruction=" + oldReconstruction,
                                            "render.ray_reconstruction=maybe")}) {
                    var rejected = SettingsFile.decode(malformed);
                    assertEquals(RenderSettings.defaults(), rejected.settings());
                    assertFalse(rejected.resetReason().isEmpty());
                }
            }
        }
        // A current file cannot use the old boolean name to claim the opposite behavior.
        var current = SettingsFile.encode(RenderSettings.defaults());
        var rejected = SettingsFile.decode(current.replace(
                "diagnostics.native_noisy_output=false\n", "render.ray_reconstruction=true\n"));
        assertEquals(RenderSettings.defaults(), rejected.settings());
        assertFalse(rejected.resetReason().isEmpty());
    }
    @Test
    void powerDistanceTreeIsTheOnlyCanonicalSampler() {
        var defaults = RenderSettings.defaults();
        assertEquals(RenderSettings.LightSampling.TREE, defaults.lightSampling());
        assertSame(defaults, defaults.withLightSampling(RenderSettings.LightSampling.TREE));
        assertEquals(1, RenderSettings.LightSampling.TREE.wireId);
        assertArrayEquals(new RenderSettings.LightSampling[] {RenderSettings.LightSampling.TREE},
                          RenderSettings.LightSampling.values());
        assertThrows(NullPointerException.class, () -> defaults.withLightSampling(null));
        assertTrue(SettingsFile.encode(defaults).contains("render.light_sampling=TREE\n"));
    }
    @Test
    void retiredSamplersMigrateWithoutResettingOtherValues(@TempDir Path dir) throws Exception {
        var expected = RenderSettings.defaults()
                               .withRenderer(RenderSettings.Renderer.RESTIR_PT)
                               .with(RenderSettings.Control.BOUNCES, 32)
                               .with(RenderSettings.Control.SATURATION, 8)
                               .with(RenderSettings.Control.TERRAIN_BATCHES_PER_FRAME, 16)
                               .with(RenderSettings.Control.EXPOSURE_EV, -4)
                               .with(RenderSettings.Control.HDR, 1)
                               .withOpacityMicromap(false)
                               .withNativeNoisyOutput(true)
                               .withDlssQuality(RenderSettings.DlssQuality.QUALITY);
        for (int version : new int[] {8, 9, 10, 11}) {
            for (String retired : new String[] {"GRID", "TREE_SPHERE"}) {
                String original = legacyFile(expected, version);
                String legacy = original.replace("render.light_sampling=TREE",
                                                 "render.light_sampling=" + retired);
                var file = dir.resolve("settings-" + version + "-" + retired + ".properties");
                Files.writeString(file, legacy);
                var loaded = SettingsFile.load(file);
                assertEquals("", loaded.resetReason());
                assertEquals(expected, loaded.settings());
                var wire = settingsBuffer();
                loaded.settings().write(wire, false, RenderSettings.View.OUTPUT);
                assertEquals(1, PrimeSettings.light_sampling(view(wire)));
                SettingsFile.save(file, loaded.settings());
                String canonical = Files.readString(file);
                assertTrue(canonical.contains("version=12\n"));
                assertTrue(canonical.contains("render.light_sampling=TREE\n"));
                assertFalse(canonical.contains(retired));
                assertEquals(expected, SettingsFile.load(file).settings());
            }
        }
        assertEquals(RenderSettings.LightSampling.TREE,
                     RenderSettings.LightSampling.fromKey("GRID"));
        assertEquals(RenderSettings.LightSampling.TREE,
                     RenderSettings.LightSampling.fromKey("TREE_SPHERE"));
        assertThrows(IllegalArgumentException.class,
                     () -> RenderSettings.LightSampling.fromKey("tree_sphere"));
        // Migration does not conceal another invalid required field.
        var malformed =
                SettingsFile.decode(legacyFile(expected, 8).replace("render.bounces=32\n", ""));
        assertEquals(RenderSettings.defaults(), malformed.settings());
        assertFalse(malformed.resetReason().isEmpty());
    }
    @Test
    void globalResetDiagnosticDefaultsOffAndHasIndependentPersistenceAndAbi() {
        var defaults = RenderSettings.defaults();
        assertFalse(defaults.ignoreGlobalHistoryResets());
        assertSame(defaults, defaults.withIgnoreGlobalHistoryResets(false));
        var enabled = defaults.withIgnoreGlobalHistoryResets(true);
        assertNotEquals(defaults, enabled);
        assertSame(enabled, enabled.withIgnoreGlobalHistoryResets(true));
        var before = settingsBuffer();
        var after = settingsBuffer();
        for (boolean offline : new boolean[] {false, true}) {
            defaults.write(before, offline, RenderSettings.View.OUTPUT);
            enabled.write(after, offline, RenderSettings.View.OUTPUT);
            assertEquals(1, PrimeSettings.ignore_global_history_resets(view(after)));
            assertOnlyFieldChanged(before, after, 104);
        }
        var copied = enabled.withRenderer(RenderSettings.Renderer.RESTIR_PT)
                             .withOpacityMicromap(false)
                             .withNativeNoisyOutput(true)
                             .withDlssQuality(RenderSettings.DlssQuality.QUALITY)
                             .withLightSampling(RenderSettings.LightSampling.TREE)
                             .with(RenderSettings.Control.BOUNCES, 32);
        assertTrue(copied.ignoreGlobalHistoryResets());
        var loaded = SettingsFile.decode(SettingsFile.encode(copied));
        assertEquals("", loaded.resetReason());
        assertEquals(copied, loaded.settings());
        assertEquals(copied.hashCode(), loaded.settings().hashCode());
        assertFalse(copied.withIgnoreGlobalHistoryResets(false).ignoreGlobalHistoryResets());
        String current = SettingsFile.encode(defaults);
        for (var method : RenderSettings.LightSampling.values()) {
            String previous = legacyFile(defaults, 8)
                                      .replace("render.light_sampling=TREE\n",
                                               "render.light_sampling=" + method.name() + "\n");
            var migrated = SettingsFile.decode(previous);
            assertEquals("", migrated.resetReason());
            assertEquals(defaults.withLightSampling(method), migrated.settings());
        }
    }
    @Test
    void spatialOnlyDiagnosticDefaultsOffHasIndependentPersistenceAndAbi() {
        var defaults = RenderSettings.defaults();
        assertFalse(defaults.restirSpatialOnly());
        assertSame(defaults, defaults.withRestirSpatialOnly(false));
        var enabled = defaults.withRestirSpatialOnly(true);
        assertNotEquals(defaults, enabled);
        assertSame(enabled, enabled.withRestirSpatialOnly(true));
        var before = settingsBuffer();
        var after = settingsBuffer();
        for (var renderer : RenderSettings.Renderer.values()) {
            for (boolean offline : new boolean[] {false, true}) {
                var original = defaults.withRenderer(renderer);
                original.write(before, offline, RenderSettings.View.OUTPUT);
                original.withRestirSpatialOnly(true).write(after, offline,
                                                           RenderSettings.View.OUTPUT);
                assertEquals(1, PrimeSettings.restir_spatial_only(view(after)));
                assertOnlyFieldChanged(before, after, 108);
                assertEquals(0, PrimeSettings.native_noisy_output(view(after)));
            }
        }
        var copied = enabled.withRenderer(RenderSettings.Renderer.RESTIR_PT)
                             .withOpacityMicromap(false)
                             .withNativeNoisyOutput(true)
                             .withDlssQuality(RenderSettings.DlssQuality.QUALITY)
                             .withLightSampling(RenderSettings.LightSampling.TREE)
                             .withIgnoreGlobalHistoryResets(true)
                             .with(RenderSettings.Control.BOUNCES, 32);
        assertTrue(copied.restirSpatialOnly());
        var loaded = SettingsFile.decode(SettingsFile.encode(copied));
        assertEquals("", loaded.resetReason());
        assertEquals(copied, loaded.settings());
        assertEquals(copied.hashCode(), loaded.settings().hashCode());
        assertFalse(copied.withRestirSpatialOnly(false).restirSpatialOnly());
        String current = SettingsFile.encode(copied);
        for (String invalid : new String[] {
                     current.replace("restir_pt.spatial_only=true\n", ""),
                     current.replace("restir_pt.spatial_only=true", "restir_pt.spatial_only=maybe"),
                     current.replace("restir_pt.spatial_only=true", "restir_pt.spatial_only=1")}) {
            var rejected = SettingsFile.decode(invalid);
            assertEquals(defaults, rejected.settings());
            assertFalse(rejected.resetReason().isEmpty());
        }
    }
    @Test
    void spatialOnlyDiagnosticMigratesMissingLegacyFieldWithoutResettingOtherValues() {
        for (int version : new int[] {8, 9, 10}) {
            var expected = RenderSettings.defaults()
                                   .withRenderer(RenderSettings.Renderer.RESTIR_PT)
                                   .withNativeNoisyOutput(true)
                                   .withIgnoreGlobalHistoryResets(version != 8)
                                   .with(RenderSettings.Control.BOUNCES, 32);
            String legacy = legacyFile(expected, version);
            assertFalse(legacy.contains("diagnostics.restir_spatial_only"));
            var loaded = SettingsFile.decode(legacy);
            assertEquals("", loaded.resetReason());
            assertEquals(expected, loaded.settings());
            assertFalse(loaded.settings().restirSpatialOnly());
            String saved = SettingsFile.encode(loaded.settings());
            assertTrue(saved.contains("version=12\n"));
            assertTrue(saved.contains("restir_pt.spatial_only=false\n"));
            assertEquals(expected, SettingsFile.decode(saved).settings());
        }
    }
    @Test
    void shortcutRequiresBothModifiersAndEscapeKeepsSnapshot() {
        var mode = new OfflineMode();
        assertFalse(mode.shortcut(true, true, false));
        assertFalse(mode.shortcut(true, false, true));
        assertTrue(mode.shortcut(true, true, true));
        assertTrue(mode.requested());
        assertFalse(mode.desired(false));
        assertTrue(mode.desired(true));
        assertFalse(mode.active());
        mode.committed(true);
        assertFalse(mode.shortcut(false, false, false));
        assertTrue(mode.active());
        assertTrue(mode.requested());
        assertTrue(mode.shortcut(true, true, true));
        assertFalse(mode.requested());
        assertTrue(mode.active());
        mode.committed(false);
        mode.reset();
        assertFalse(mode.active());
    }
    @Test
    void frozenFrameOnlyChangesExtentAndSequence() {
        byte[] frame =
                Packets.frame(123, 1024.25, -30.5, 7.25, new float[] {0, 0, -1},
                              new float[] {1, 0, 0}, new float[] {0, 1, 0}, 1.1f, 1920, 1080, 73);
        var packet = ByteBuffer.allocateDirect((int)PrimeFrame.SIZE).order(ByteOrder.nativeOrder());
        packet.put(frame).clear();
        PrimeFrame.solar_hour_angle(view(packet), 1.3f);
        Packets.resizeFrozenFrame(packet, 701, 999, 0);
        assertArrayEquals(
                java.util.Arrays.copyOf(frame, 80),
                view(packet).asSlice(0, 80).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        assertEquals(701, PrimeFrame.width(view(packet)));
        assertEquals(999, PrimeFrame.height(view(packet)));
        assertEquals(0, PrimeFrame.sample_index(view(packet)));
        assertEquals(frame.length, packet.position());
        assertEquals(1.3f, PrimeFrame.solar_hour_angle(view(packet)));
    }
    private static ByteBuffer settingsBuffer() {
        return ByteBuffer.allocateDirect((int)PrimeSettings.SIZE).order(ByteOrder.nativeOrder());
    }
    private static MemorySegment view(ByteBuffer buffer) {
        return MemorySegment.ofBuffer(buffer.duplicate().clear());
    }
}
