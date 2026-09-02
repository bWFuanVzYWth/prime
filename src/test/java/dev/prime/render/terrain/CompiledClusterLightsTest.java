package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import dev.prime.render.shader.ShaderAbi;
import org.junit.jupiter.api.Test;

final class CompiledClusterLightsTest {
    private static final int TINT_ARGB = 0xa012_3456;
    private static final int TEXTURE_ID = 91;
    private static final CapturedSprite TEXTURE = new CapturedSprite(
            new SpriteId("prime", "compiled_light_fixture"),
            TEXTURE_ID,
            16,
            16,
            false,
            new int[] {0},
            null);

    @Test
    void relocationChangesOnlyTheFiveHeaderPointers() {
        CompiledClusterLights lights = oneEmitterLights();
        int[] encoded = lights.relocate(0L);
        int[] expected = encoded.clone();
        for (int pointer = 0; pointer < 5; pointer++) {
            putLong(expected, pointer * 2, 0x1000L + getLong(encoded, pointer * 2));
        }

        int[] relocated = lights.relocate(0x1000L);

        assertNotSame(encoded, relocated);
        assertArrayEquals(expected, relocated);
        assertArrayEquals(encoded, lights.relocate(0L));
    }

    @Test
    void exposesExactEmitterTextureAndTintIdentity() {
        CompiledClusterLights lights = oneEmitterLights();

        assertEquals(
                new CompiledClusterLights.EmitterMaterial(
                        PrimitivePacking.packTint(TINT_ARGB) & 0x00ff_ffff,
                        TEXTURE_ID),
                lights.emitterMaterial(0));
    }

    @Test
    void relocationReplacesEmitterRgbWithExactTintIdWithoutMutatingSource() {
        CompiledClusterLights lights = oneEmitterLights();
        int[] encoded = lights.relocate(0L);
        int tintWord = emitterStart(encoded)
                + ShaderAbi.LIGHT_EMITTER_UVS_TINT_OFFSET / Integer.BYTES + 3;

        int[] relocated = lights.relocate(0x1000L, packedRgba -> {
            assertEquals(PrimitivePacking.packTint(TINT_ARGB), packedRgba);
            return 37;
        });

        assertEquals(37, relocated[tintWord]);
        assertArrayEquals(encoded, lights.relocate(0L));
    }

    @Test
    void relocationPublishesEmitterRelationOffsetsWithoutMutatingSource() {
        CompiledClusterLights lights = oneEmitterLights();
        int[] encoded = lights.relocate(0L);
        int relationWord = emitterStart(encoded)
                + ShaderAbi.LIGHT_EMITTER_RELATION_OFFSET_OFFSET / Integer.BYTES;

        int[] relocated = lights.relocate(0x1000L, null, new int[] {123});

        assertEquals(123, relocated[relationWord]);
        assertEquals(0, lights.relocate(0L)[relationWord]);
        assertThrows(
                IllegalArgumentException.class,
                () -> lights.relocate(0x1000L, null, new int[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> lights.relocate(0x1000L, null, new int[] {0x0100_0000}));
    }

    private static CompiledClusterLights oneEmitterLights() {
        CpuSectionLights.Builder builder = new CpuSectionLights.Builder();
        builder.addTriangle(
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                1.0F,
                0.0F,
                0.0F,
                0,
                0,
                0,
                TINT_ARGB,
                false,
                15,
                TEXTURE,
                null);
        return CompiledClusterLights.compile(builder.build());
    }

    private static int emitterStart(int[] words) {
        return Math.toIntExact(getLong(words, 6) / Integer.BYTES);
    }

    private static long getLong(int[] words, int offset) {
        return Integer.toUnsignedLong(words[offset])
                | (long) words[offset + 1] << 32;
    }

    private static void putLong(int[] words, int offset, long value) {
        words[offset] = (int) value;
        words[offset + 1] = (int) (value >>> 32);
    }
}
