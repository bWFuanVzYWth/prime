package dev.primept.settings;

import static org.junit.jupiter.api.Assertions.*;
import dev.primept.capture.Packets;
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
            var wire =
                    ByteBuffer.allocate(RenderSettings.WIRE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            settings.write(wire, false, RenderSettings.View.OUTPUT);
            assertEquals(budget, wire.getInt(8));
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
                               .withDlssQuality(RenderSettings.DlssQuality.QUALITY);
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
                     valid.replace("version=5", "version=0"),
                     valid.replace("version=5", "version=4"),
                     valid.replace("version=5", "version=6"), valid.replace("version=5", ""),
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
                     valid + "render.bounces=NaN\n", valid + "bad=\\uXYZW\n"}) {
            var loaded = SettingsFile.decode(broken);
            assertEquals(RenderSettings.defaults(), loaded.settings());
            assertFalse(loaded.resetReason().isEmpty());
        }
        assertEquals(changed, SettingsFile.decode(valid + "unrelated=ignored\n").settings());
    }
    @Test
    void immutableControlsAndLittleEndianFfiLayout() {
        var original = RenderSettings.defaults();
        var changed = original.with(RenderSettings.Control.EXPOSURE_EV, 8)
                              .with(RenderSettings.Control.SUN_EV, -4);
        assertEquals(0, original.value(RenderSettings.Control.EXPOSURE_EV));
        assertThrows(IllegalArgumentException.class,
                     () -> original.with(RenderSettings.Control.BOUNCES, 0));
        var bytes = ByteBuffer.allocate(RenderSettings.WIRE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        changed.write(bytes, true, RenderSettings.View.NORMAL);
        assertEquals(72, bytes.position());
        assertEquals(5, bytes.getInt(0));
        assertEquals(1, bytes.getInt(4));
        assertEquals(12, bytes.getInt(8));
        assertEquals(1, bytes.getInt(12));
        assertEquals(4.0f, bytes.getFloat(16));
        assertEquals(.75f, bytes.getFloat(20));
        assertEquals(.08f, bytes.getFloat(24));
        assertEquals(3, bytes.getInt(28));
        assertEquals(.5f, bytes.getFloat(32));
        assertEquals(1f, bytes.getFloat(36));
        assertEquals(128f, bytes.getFloat(40));
        assertEquals(0x13572468, bytes.getInt(44));
        assertEquals(30, bytes.getInt(48));
        assertEquals(0, bytes.getInt(52));
        assertEquals(1, bytes.getInt(56));
        assertEquals(1, bytes.getInt(60));
        assertEquals(3, bytes.getInt(64));
        assertEquals(8, bytes.getInt(68));
    }
    @Test
    void terrainBatchBudgetDefaultsRangePersistenceAndIndependentWire(@TempDir Path dir) {
        var control = RenderSettings.Control.TERRAIN_BATCHES_PER_FRAME;
        var defaults = RenderSettings.defaults();
        assertEquals(8, defaults.value(control));
        assertEquals(8,
                     SettingsFile.load(dir.resolve("absent.properties")).settings().value(control));
        var before = ByteBuffer.allocate(RenderSettings.WIRE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var after = ByteBuffer.allocate(RenderSettings.WIRE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int budget = 1; budget <= 128; ++budget) {
            var changed = defaults.with(control, budget);
            assertEquals(changed, SettingsFile.decode(SettingsFile.encode(changed)).settings());
            for (boolean offline : new boolean[] {false, true}) {
                defaults.write(before, offline, RenderSettings.View.OUTPUT);
                changed.write(after, offline, RenderSettings.View.OUTPUT);
                assertEquals(72, after.position());
                assertEquals(budget, after.getInt(68));
                assertArrayEquals(java.util.Arrays.copyOf(before.array(), 68),
                                  java.util.Arrays.copyOf(after.array(), 68));
            }
        }
        assertNotEquals(defaults, defaults.with(control, 1));
        assertEquals(8, defaults.value(control));
        assertThrows(IllegalArgumentException.class, () -> defaults.with(control, 0));
        assertThrows(IllegalArgumentException.class, () -> defaults.with(control, 129));
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
        var bytes = ByteBuffer.allocate(RenderSettings.WIRE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        disabled.write(bytes, true, RenderSettings.View.OUTPUT);
        assertEquals(0, bytes.getInt(56));
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
        var bytes = ByteBuffer.allocate(RenderSettings.WIRE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var quality : RenderSettings.DlssQuality.values()) {
            var changed = disabled.withDlssQuality(quality);
            assertFalse(changed.rayReconstruction());
            assertEquals(changed, SettingsFile.decode(SettingsFile.encode(changed)).settings());
            changed.write(bytes, false, RenderSettings.View.OUTPUT);
            assertEquals(0, bytes.getInt(60));
            assertEquals(quality.ordinal(), bytes.getInt(64));
            assertEquals(quality, changed.withRayReconstruction(true).dlssQuality());
        }
        assertTrue(defaults.rayReconstruction());
        assertThrows(NullPointerException.class, () -> defaults.withDlssQuality(null));
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
        var packet = ByteBuffer.wrap(frame.clone()).order(ByteOrder.LITTLE_ENDIAN);
        packet.putFloat(100, 1.3f);
        Packets.resizeFrozenFrame(packet, 701, 999, 0);
        assertArrayEquals(java.util.Arrays.copyOf(frame, 88),
                          java.util.Arrays.copyOf(packet.array(), 88));
        assertEquals(701, packet.getInt(88));
        assertEquals(999, packet.getInt(92));
        assertEquals(0, packet.getInt(96));
        assertEquals(frame.length, packet.position());
        assertEquals(1.3f, packet.getFloat(100));
    }
}
