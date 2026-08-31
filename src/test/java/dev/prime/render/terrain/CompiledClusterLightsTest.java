package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.shader.ShaderAbi;
import org.junit.jupiter.api.Test;

final class CompiledClusterLightsTest {
    private static final int HEADER_WORDS = 12;
    private static final int NODE_DIRECTION_WORD =
            ShaderAbi.LIGHT_NODE_DIRECTION_CHILD_RESERVED_OFFSET / Integer.BYTES;
    private static final int NODE_CHILD_WORD = NODE_DIRECTION_WORD + 1;

    @Test
    void relocationChangesOnlyTheFiveHeaderPointers() {
        int[] relative = validOneEmitterPayload();
        long[] offsets = {48L, 80L, 88L, 96L, 192L};
        relative[26] = Float.floatToRawIntBits(0.25F);
        CompiledClusterLights lights = CompiledClusterLights.fromEncoded(
                relative,
                new CompiledClusterLights.Summary(
                        1, -1.0F, -2.0F, -3.0F, 1.0F, 2.0F, 3.0F, 4.0F));

        int[] encoded = lights.encodedWords();
        int[] relocated = lights.relocate(0x1000L);

        assertNotSame(encoded, relocated);
        assertArrayEquals(relative, encoded);
        for (int pointer = 0; pointer < 5; pointer++) {
            assertEquals(
                    0x1000L + offsets[pointer],
                    getLong(relocated, pointer * 2));
        }
        assertEquals(relative[10], relocated[10]);
        assertEquals(relative[11], relocated[11]);
        assertEquals(relative[26], relocated[26]);
    }

    @Test
    void exposesExactEmitterTextureAndTintIdentity() {
        int[] relative = validOneEmitterPayload();
        relative[24 + 19] = 0x0012_3456;
        relative[24 + 23] = 91;
        CompiledClusterLights lights = CompiledClusterLights.fromEncoded(
                relative,
                new CompiledClusterLights.Summary(
                        1, -1.0F, -2.0F, -3.0F, 1.0F, 2.0F, 3.0F, 4.0F));

        assertEquals(
                new CompiledClusterLights.EmitterMaterial(0x0012_3456, 91),
                lights.emitterMaterial(0));
    }

    @Test
    void encodedPayloadValidationRejectsBrokenIdentity() {
        int[] header = new int[12];
        header[11] = 2;
        CompiledClusterLights.Summary oneEmitter =
                new CompiledClusterLights.Summary(
                        1, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(header, oneEmitter));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(
                        new int[] {1}, CompiledClusterLights.EMPTY.summary()));
    }

    @Test
    void encodedPayloadValidationRejectsBrokenAbiLayout() {
        int[] relative = validOneEmitterPayload();
        CompiledClusterLights.Summary oneEmitter =
                new CompiledClusterLights.Summary(
                        1, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);

        putLong(relative, 2, 76L);

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(relative, oneEmitter));
    }

    @Test
    void encodedPayloadValidationRejectsBrokenEmitterReference() {
        int[] relative = validOneEmitterPayload();
        CompiledClusterLights.Summary oneEmitter =
                new CompiledClusterLights.Summary(
                        1, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);

        relative[44] = 256;

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(relative, oneEmitter));
    }

    @Test
    void encodedPayloadValidationRejectsRootDirectionSummaryMismatch() {
        int[] relative = validOneEmitterPayload();
        relative[HEADER_WORDS + NODE_DIRECTION_WORD] = 0;

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(
                        relative,
                        new CompiledClusterLights.Summary(
                                1,
                                0.0F,
                                0.0F,
                                0.0F,
                                1.0F,
                                1.0F,
                                1.0F,
                                1.0F)));
    }

    @Test
    void encodedPayloadValidationRejectsNonfiniteNodeCentroid() {
        int[] relative = validOneEmitterPayload();
        relative[HEADER_WORDS] = Float.floatToRawIntBits(Float.NaN);

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(
                        relative,
                        new CompiledClusterLights.Summary(
                                1,
                                0.0F,
                                0.0F,
                                0.0F,
                                1.0F,
                                1.0F,
                                1.0F,
                                1.0F)));
    }

    private static int[] validOneEmitterPayload() {
        int[] relative = new int[816];
        long[] offsets = {48L, 80L, 88L, 96L, 192L};
        for (int pointer = 0; pointer < offsets.length; pointer++) {
            putLong(relative, pointer * 2, offsets[pointer]);
        }
        relative[11] = 1;
        relative[15] = Float.floatToRawIntBits(1.0F);
        relative[HEADER_WORDS + NODE_DIRECTION_WORD] = LightDirection.FULL;
        relative[HEADER_WORDS + NODE_CHILD_WORD] = CpuLightTree.LEAF_FLAG;
        relative[20] = 0;
        relative[21] = 1;
        relative[22] = 0;
        relative[23] = Float.floatToRawIntBits(1.0F);
        populateEmitter(relative, 24);
        relative[48] = 0;
        relative[49] = 0;
        return relative;
    }

    private static void populateEmitter(int[] words, int base) {
        words[base + 3] = Float.floatToRawIntBits(0.5F);
        words[base + 4] = Float.floatToRawIntBits(1.0F);
        words[base + 9] = Float.floatToRawIntBits(1.0F);
        words[base + 11] = Float.floatToRawIntBits(1.0F);
        words[base + 14] = Float.floatToRawIntBits(1.0F);
        words[base + 20] = 0;
        words[base + 21] = 0;
        words[base + 22] = 0;
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
