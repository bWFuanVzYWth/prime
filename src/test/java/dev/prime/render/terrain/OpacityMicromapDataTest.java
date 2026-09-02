package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.EXTOpacityMicromap;

final class OpacityMicromapDataTest {
    @Test
    void twoStateBlocksPackOneBitPerMicrotriangle() {
        assertEquals(
                EXTOpacityMicromap.VK_OPACITY_MICROMAP_FORMAT_2_STATE_EXT,
                OpacityMicromapData.TWO_STATE_FORMAT);
        assertEquals(
                EXTOpacityMicromap.VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT,
                OpacityMicromapData.FOUR_STATE_FORMAT);
        assertEquals(
                OpacityMicromapData.MICRO_TRIANGLE_COUNT / Byte.SIZE,
                OpacityMicromapData.TWO_STATE_BYTES_PER_BLOCK);
        assertEquals(
                OpacityMicromapData.MICRO_TRIANGLE_COUNT * 2 / Byte.SIZE,
                OpacityMicromapData.FOUR_STATE_BYTES_PER_BLOCK);
    }

    @Test
    void isolatedMipZeroTexelKeepsItsTwoAlignedMicrotriangles() {
        OpacityMicromapData.BakedBlock block = OpacityMicromapData.bakeCoverage(
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                0.0F,
                1.0F,
                1,
                (frame, u, v) -> texel(u) == 4 && texel(v) == 4);
        assertEquals(OpacityMicromapData.TWO_STATE_FORMAT, block.format());
        int opaqueMicrotriangles = 0;
        for (int index = 0; index < OpacityMicromapData.MICRO_TRIANGLE_COUNT; index++) {
            opaqueMicrotriangles += block.state(index);
        }
        assertEquals(2, opaqueMicrotriangles);
    }

    @Test
    void animatedCoverageUsesUnknownOnlyWhereFramesDisagree() {
        OpacityMicromapData.BakedBlock block = OpacityMicromapData.bakeCoverage(
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                0.0F,
                1.0F,
                2,
                (frame, u, v) -> frame == 0 && texel(u) == 4 && texel(v) == 4);
        assertEquals(OpacityMicromapData.FOUR_STATE_FORMAT, block.format());
        int unknownMicrotriangles = 0;
        int transparentMicrotriangles = 0;
        for (int index = 0; index < OpacityMicromapData.MICRO_TRIANGLE_COUNT; index++) {
            int state = block.state(index);
            unknownMicrotriangles += state == 3 ? 1 : 0;
            transparentMicrotriangles += state == 0 ? 1 : 0;
        }
        assertEquals(2, unknownMicrotriangles);
        assertEquals(
                OpacityMicromapData.MICRO_TRIANGLE_COUNT - 2,
                transparentMicrotriangles);
    }

    @Test
    void mixedBlocksPackVariableStridesAndFormats() {
        OpacityMicromapData.BakedBlock twoState = OpacityMicromapData.bakeCoverage(
                0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F,
                1,
                (frame, u, v) -> texel(u) == 4 && texel(v) == 4);
        OpacityMicromapData.BakedBlock fourState = OpacityMicromapData.bakeCoverage(
                0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F,
                2,
                (frame, u, v) -> frame == 0 && texel(u) == 4 && texel(v) == 4);
        OpacityMicromapData data = OpacityMicromapData.pack(
                new OpacityMicromapData.BakedBlock[] {twoState, fourState},
                new int[] {0, 1});

        assertEquals(
                OpacityMicromapData.TWO_STATE_BYTES_PER_BLOCK
                        + OpacityMicromapData.FOUR_STATE_BYTES_PER_BLOCK,
                data.blocks().length);
        assertArrayEquals(
                new int[] {0, OpacityMicromapData.TWO_STATE_BYTES_PER_BLOCK},
                data.blockOffsets());
        assertArrayEquals(
                new int[] {
                    OpacityMicromapData.TWO_STATE_FORMAT,
                    OpacityMicromapData.FOUR_STATE_FORMAT
                },
                data.blockFormats());
        assertEquals(1, data.blockCount(OpacityMicromapData.TWO_STATE_FORMAT));
        assertEquals(1, data.blockCount(OpacityMicromapData.FOUR_STATE_FORMAT));
    }

    @Test
    void largerTemplatesPreserveNativeMicrotriangleDensity() {
        int level = OpacityMicromapData.SUBDIVISION_LEVEL + 2;
        OpacityMicromapData.BakedBlock block = OpacityMicromapData.bakeCoverage(
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                0.0F,
                1.0F,
                level,
                1,
                (frame, u, v) -> texel(u) == texel(v));

        assertEquals(level, block.subdivisionLevel());
        assertEquals(
                OpacityMicromapData.blockByteSize(
                        OpacityMicromapData.TWO_STATE_FORMAT, level),
                block.states().length);
        for (int index = 0;
                index < OpacityMicromapData.microTriangleCount(level);
                index++) {
            int state = block.state(index);
            assertTrue(state == 0 || state == 1);
        }
    }

    @Test
    void packedBlocksRetainIndependentSubdivisionLevels() {
        OpacityMicromapData.BakedBlock levelFour = OpacityMicromapData.bakeCoverage(
                0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F,
                OpacityMicromapData.SUBDIVISION_LEVEL,
                1,
                (frame, u, v) -> texel(u) == 4);
        int levelSix = OpacityMicromapData.SUBDIVISION_LEVEL + 2;
        OpacityMicromapData.BakedBlock larger = OpacityMicromapData.bakeCoverage(
                0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F,
                levelSix,
                1,
                (frame, u, v) -> texel(v) == 4);

        OpacityMicromapData data = OpacityMicromapData.pack(
                new OpacityMicromapData.BakedBlock[] {levelFour, larger},
                new int[] {0, 1});

        assertArrayEquals(
                new int[] {OpacityMicromapData.SUBDIVISION_LEVEL, levelSix},
                data.blockSubdivisionLevels());
        assertEquals(
                levelFour.states().length + larger.states().length,
                data.blocks().length);
    }

    @Test
    void birdCurveMapsEverySubdivisionCellExactlyOnce() {
        boolean[] seen = new boolean[OpacityMicromapData.MICRO_TRIANGLE_COUNT];
        float[] barycentric = new float[3];
        for (int cell = 0; cell < OpacityMicromapData.MICRO_TRIANGLE_COUNT; cell++) {
            EmissionDistribution.cell(cell).samplePoint(0, barycentric);
            int index = OpacityMicromapData.barycentricsToSpaceFillingCurveIndex(
                    barycentric[1],
                    barycentric[2],
                    OpacityMicromapData.SUBDIVISION_LEVEL);
            assertTrue(index >= 0 && index < seen.length);
            assertFalse(seen[index], "Two microtriangles mapped to bird index " + index);
            seen[index] = true;
        }
        for (boolean mapped : seen) {
            assertTrue(mapped);
        }
    }

    @Test
    void fullyUnknownFallbackPreservesOneIndexPerCutoutTriangle() {
        OpacityMicromapData data = OpacityMicromapData.fullyUnknown(17);
        assertEquals(0, data.blockCount());
        assertEquals(17, data.triangleCount());
        assertEquals(17L * Integer.BYTES, data.byteSize());
    }

    @Test
    void uploadValidationRejectsMutatedBlockReferences() {
        OpacityMicromapData data = OpacityMicromapData.fullyUnknown(1);
        data.triangleIndices()[0] = 0;
        assertThrows(
                IllegalArgumentException.class,
                data::requireValidTriangleIndices);
        data.triangleIndices()[0] = Integer.MIN_VALUE;
        assertThrows(
                IllegalArgumentException.class,
                data::requireValidTriangleIndices);
    }

    private static int texel(float coordinate) {
        float clamped = Math.max(0.0F, Math.min(Math.nextDown(1.0F), coordinate));
        return Math.min(
                (int) (clamped * EmissionDistribution.SUBDIVISION),
                EmissionDistribution.SUBDIVISION - 1);
    }
}
