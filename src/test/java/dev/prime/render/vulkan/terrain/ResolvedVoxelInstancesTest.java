package dev.prime.render.vulkan.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.terrain.CpuVoxelInstances;
import org.junit.jupiter.api.Test;

final class ResolvedVoxelInstancesTest {
    @Test
    void resolvesExactTintIdsAndBorrowsTheImmutableSource() {
        int[] meshes = {2, 4};
        int[] packedTints = {0x0011_2233, 0x0044_5566};
        float[] translations = {1.0F, 2.0F, 3.0F, -4.0F, -5.0F, -6.0F};
        CpuVoxelInstances source = new CpuVoxelInstances(meshes, packedTints, translations);

        ResolvedVoxelInstances result = ResolvedVoxelInstances.resolve(
                source, packedRgba -> packedRgba == (packedTints[0] | 0xff00_0000)
                        ? 7
                        : 11);

        assertSame(source, result.source());
        assertEquals(2, result.count());
        assertEquals(2, result.meshIndex(0));
        assertEquals(7, result.tintId(0));
        assertEquals(11, result.tintId(1));
        assertEquals(1.0F, result.translationX(0));
        assertEquals(-6.0F, result.translationZ(1));
    }

    @Test
    void emptyInputUsesTheSharedEmptySnapshot() {
        assertSame(
                ResolvedVoxelInstances.EMPTY,
                ResolvedVoxelInstances.resolve(CpuVoxelInstances.EMPTY, ignored -> 1));
    }

    @Test
    void preservesPerInstancePreviousTranslationAndMotionState() {
        CpuVoxelInstances source = CpuVoxelInstances.translated(
                new int[] {0, 0},
                new int[] {0, 0},
                new float[] {4.0F, 5.0F, 6.0F, 7.0F, 8.0F, 9.0F},
                new float[] {1.0F, 2.0F, 3.0F, 7.0F, 8.0F, 9.0F},
                new boolean[] {true, false});

        ResolvedVoxelInstances result = ResolvedVoxelInstances.resolve(source, ignored -> 0);

        assertTrue(result.hasMotion(0));
        assertFalse(result.hasMotion(1));
        assertEquals(1.0F, result.previousTranslationX(0));
        assertEquals(3.0F, result.previousTranslationZ(0));
        assertEquals(7.0F, result.previousTranslationX(1));
        assertEquals(9.0F, result.previousTranslationZ(1));
    }

    @Test
    void rejectsInconsistentResolvedArrays() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResolvedVoxelInstances(
                        new CpuVoxelInstances(
                                new int[] {0}, new int[] {0}, new float[3]),
                        new int[0]));
    }
}
