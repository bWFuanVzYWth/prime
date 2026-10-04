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
                               .withRayReconstruction(false)
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
                     valid.replace("version=7", "version=0"),
                     valid.replace("version=7", "version=6"),
                     valid.replace("version=7", "version=8"),
                     valid.replace("version=7", ""),
                     valid.replace("render.bounces=12", ""),
                     valid.replace("render.bounces=12", "render.bounces=65"),
                     valid.replace("terrain.batches_per_frame=8", ""),
                     valid.replace("terrain.batches_per_frame=8", "terrain.batches_per_frame=0"),
                     valid.replace("terrain.batches_per_frame=8", "terrain.batches_per_frame=129"),
                     valid.replace("terrain.batches_per_frame=8", "terrain.batches_per_frame=NaN"),
                     valid.replace("renderer.path_tracing=false", "renderer.path_tracing=maybe"),
                     valid.replace("render.opacity_micromap=true", ""),
                     valid.replace("render.opacity_micromap=true", "render.opacity_micromap=maybe"),
                     valid.replace("render.ray_reconstruction=true", ""),
                     valid.replace("render.ray_reconstruction=true",
                                   "render.ray_reconstruction=maybe"),
                     valid.replace("render.dlss_quality=PERFORMANCE", ""),
                     valid.replace("render.dlss_quality=PERFORMANCE",
                                   "render.dlss_quality=UNKNOWN"),
                     valid.replace("render.light_sampling=GRID", ""),
                     valid.replace("render.light_sampling=GRID", "render.light_sampling=UNKNOWN"),
                     valid.replace("render.light_sampling=GRID", "render.light_sampling=2"),
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
        assertEquals(1, PrimeSettings.ray_reconstruction(view(bytes)));
        assertEquals(3, PrimeSettings.reconstruction_quality(view(bytes)));
        assertEquals(8, PrimeSettings.terrain_batches_per_frame(view(bytes)));
        assertEquals(1f, PrimeSettings.stars(view(bytes)));
        assertEquals(.6f, PrimeSettings.auto_exposure_compensation(view(bytes)));
        assertEquals(0, PrimeSettings.hdr(view(bytes)));
        assertEquals(0, PrimeSettings.hdr_reference_white(view(bytes)));
        assertEquals(0, PrimeSettings.frame_generation(view(bytes)));
        assertEquals(0, PrimeSettings.light_sampling(view(bytes)));
        assertEquals(100, PrimeSettings.SIZE);
    }
    @Test
    void saturationDefaultsPreserveSavedValuesAndIndependentWire(@TempDir Path dir)
            throws Exception {
        var control = RenderSettings.Control.SATURATION;
        var defaults = RenderSettings.defaults();
        assertEquals(7, RenderSettings.VERSION);
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
    void rayReconstructionAndQualityRoundTripPreserveIndependentSettings() {
        var defaults = RenderSettings.defaults();
        assertTrue(defaults.rayReconstruction());
        assertEquals(RenderSettings.DlssQuality.PERFORMANCE, defaults.dlssQuality());
        var disabled = defaults.withRayReconstruction(false);
        assertNotEquals(defaults, disabled);
        assertFalse(disabled.withPathTracing(false)
                            .withOpacityMicromap(false)
                            .with(RenderSettings.Control.BOUNCES, 8)
                            .rayReconstruction());
        var bytes = settingsBuffer();
        for (var quality : RenderSettings.DlssQuality.values()) {
            var changed = disabled.withDlssQuality(quality);
            assertFalse(changed.rayReconstruction());
            assertEquals(changed, SettingsFile.decode(SettingsFile.encode(changed)).settings());
            changed.write(bytes, false, RenderSettings.View.OUTPUT);
            assertEquals(0, PrimeSettings.ray_reconstruction(view(bytes)));
            assertEquals(quality.ordinal(), PrimeSettings.reconstruction_quality(view(bytes)));
            assertEquals(quality, changed.withRayReconstruction(true).dlssQuality());
        }
        assertTrue(defaults.rayReconstruction());
        assertThrows(NullPointerException.class, () -> defaults.withDlssQuality(null));
    }
    @Test
    void lightSamplingChoicePersistsAndCopiesWithoutChangingOtherWireFields() {
        var defaults = RenderSettings.defaults();
        assertEquals(RenderSettings.LightSampling.GRID, defaults.lightSampling());
        assertSame(defaults, defaults.withLightSampling(RenderSettings.LightSampling.GRID));
        assertEquals(0, RenderSettings.LightSampling.GRID.ordinal());
        assertEquals(1, RenderSettings.LightSampling.TREE.ordinal());
        assertEquals(2, RenderSettings.LightSampling.TREE_SPHERE.ordinal());
        assertThrows(NullPointerException.class, () -> defaults.withLightSampling(null));
        var before = settingsBuffer();
        var after = settingsBuffer();
        for (var method : new RenderSettings.LightSampling[] {
                     RenderSettings.LightSampling.TREE, RenderSettings.LightSampling.TREE_SPHERE}) {
            var changed = defaults.withLightSampling(method);
            assertNotEquals(defaults, changed);
            assertSame(changed, changed.withLightSampling(method));
            assertEquals(changed, SettingsFile.decode(SettingsFile.encode(changed)).settings());
            assertEquals(changed.hashCode(),
                         SettingsFile.decode(SettingsFile.encode(changed)).settings().hashCode());
            assertEquals(RenderSettings.LightSampling.GRID, defaults.lightSampling());
            assertEquals(method, changed.withPathTracing(false)
                                         .withOpacityMicromap(false)
                                         .withRayReconstruction(false)
                                         .withDlssQuality(RenderSettings.DlssQuality.QUALITY)
                                         .with(RenderSettings.Control.BOUNCES, 8)
                                         .lightSampling());
            for (boolean offline : new boolean[] {false, true}) {
                defaults.write(before, offline, RenderSettings.View.OUTPUT);
                changed.write(after, offline, RenderSettings.View.OUTPUT);
                assertEquals(method.ordinal(), PrimeSettings.light_sampling(view(after)));
                assertOnlyFieldChanged(before, after, 96);
            }
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
