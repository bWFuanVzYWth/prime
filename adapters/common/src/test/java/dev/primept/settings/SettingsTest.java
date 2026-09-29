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
    void completeFileRoundTripAndReplacement(@TempDir Path dir) throws Exception {
        var settings = RenderSettings.defaults().withPathTracing(false);
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
                     valid.replace("version=2", "version=0"),
                     valid.replace("version=2", "version=3"), valid.replace("version=2", ""),
                     valid.replace("render.bounces=12", ""),
                     valid.replace("render.bounces=12", "render.bounces=65"),
                     valid.replace("renderer.path_tracing=false", "renderer.path_tracing=maybe"),
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
        assertEquals(56, bytes.position());
        assertEquals(2, bytes.getInt(0));
        assertEquals(1, bytes.getInt(4));
        assertEquals(4, bytes.getInt(8));
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
