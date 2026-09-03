package dev.prime.render.vulkan.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.prime.render.terrain.CpuSectionMesh;
import dev.prime.render.terrain.CpuVoxelMesh;
import dev.prime.render.terrain.OpacityMicromapData;
import dev.prime.render.terrain.TriangleLayout;
import org.junit.jupiter.api.Test;

final class VoxelBlasPoolTest {
    @Test
    void keyUsesTheCompleteGpuPayload() {
        CpuVoxelMesh original = mesh(0.0F, 7, true);
        CpuVoxelMesh equal = mesh(0.0F, 7, true);

        assertEquals(
                new VoxelBlasPool.Key(original),
                new VoxelBlasPool.Key(equal));
        assertNotEquals(
                new VoxelBlasPool.Key(original),
                new VoxelBlasPool.Key(mesh(1.0F, 7, true)));
        assertNotEquals(
                new VoxelBlasPool.Key(original),
                new VoxelBlasPool.Key(mesh(0.0F, 8, true)));
        assertNotEquals(
                new VoxelBlasPool.Key(original),
                new VoxelBlasPool.Key(mesh(0.0F, 7, false)));
    }

    @Test
    void keyPreservesRawFloatingPointPayloadAndSnapshotsBorrowedArrays() {
        float[] positions = positions(0.0F);
        CpuVoxelMesh borrowed = new CpuVoxelMesh(
                positions,
                primitives(3),
                TriangleLayout.triangles(1, 0, 0),
                OpacityMicromapData.EMPTY);
        VoxelBlasPool.Key snapshot = new VoxelBlasPool.Key(borrowed);
        positions[0] = 2.0F;

        assertEquals(snapshot, new VoxelBlasPool.Key(mesh(0.0F, 3, true)));
        assertNotEquals(
                new VoxelBlasPool.Key(mesh(0.0F, 3, true)),
                new VoxelBlasPool.Key(mesh(-0.0F, 3, true)));
    }

    private static CpuVoxelMesh mesh(float firstPosition, int primitive, boolean opaque) {
        return new CpuVoxelMesh(
                positions(firstPosition),
                primitives(primitive),
                TriangleLayout.triangles(opaque ? 1 : 0, 0, opaque ? 0 : 1),
                OpacityMicromapData.EMPTY);
    }

    private static float[] positions(float first) {
        return new float[] {first, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F};
    }

    private static int[] primitives(int first) {
        int[] result = new int[CpuSectionMesh.PRIMITIVE_WORDS];
        result[0] = first;
        return result;
    }
}
