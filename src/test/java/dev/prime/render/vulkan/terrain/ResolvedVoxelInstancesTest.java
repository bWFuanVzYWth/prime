package dev.prime.render.vulkan.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.terrain.CpuVoxelInstances;
import org.junit.jupiter.api.Test;

final class ResolvedVoxelInstancesTest {
    @Test
    void resolvesExactTintIdsAndOwnsTheResolvedSnapshot() {
        int[] meshes = {2, 4};
        int[] packedTints = {0x0011_2233, 0x0044_5566};
        float[] translations = {1.0F, 2.0F, 3.0F, -4.0F, -5.0F, -6.0F};
        CpuVoxelInstances source = new CpuVoxelInstances(meshes, packedTints, translations);

        ResolvedVoxelInstances result = ResolvedVoxelInstances.resolve(
                source, packedRgba -> packedRgba == (packedTints[0] | 0xff00_0000)
                        ? 7
                        : 11);
        meshes[0] = 99;
        translations[0] = 99.0F;

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
    void rejectsInconsistentResolvedArrays() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResolvedVoxelInstances(
                        new int[] {0}, new int[0], new float[3]));
    }
}
