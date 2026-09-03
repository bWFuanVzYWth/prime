package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.vulkan.terrain.ClusterStagingLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TerrainUploadBudgetTest {
    @Test
    void stagingBudgetIncludesTheAlignmentBeforeSectionLights() {
        // A triangle contributes 36 position bytes and 32 primitive bytes. Its 16-byte-aligned
        // 12-byte light record starts at 80, not at the raw payload sum of 68.
        assertEquals(
                184L,
                ClusterStagingLayout.endOffset(
                        0L, 36L, 32L, 12L, 8L, 64L, 8L));
        assertEquals(
                252L,
                ClusterStagingLayout.endOffset(
                        184L, 36L, 32L, 0L, 0L, 0L, 0L));
    }

    @Test
    void stagingBudgetIncludesEveryCpuSegmentWithoutAddingBlasPayloads() {
        CpuClusterMesh mesh = CpuClusterMesh.fromSegments(List.of(
                mesh(1, 1, 0),
                mesh(0, 0, 1)));

        assertEquals(204L, ClusterStagingLayout.endOffset(0L, mesh, false));
        assertEquals(2, mesh.segments().size());
    }

    @Test
    void stagingBudgetIncludesReusableVoxelMeshPayloads() {
        CpuVoxelMesh voxelMesh = new CpuVoxelMesh(
                new float[9],
                new int[CpuSectionMesh.PRIMITIVE_WORDS],
                TriangleLayout.triangles(1, 0, 0),
                OpacityMicromapData.EMPTY);
        CpuClusterMesh mesh = CpuClusterMesh.fromSegments(
                List.of(),
                List.of(voxelMesh),
                new CpuVoxelInstances(
                        new int[] {0},
                        new int[] {0x00ff_ffff},
                        new float[] {0.0F, 0.0F, 0.0F}));

        assertEquals(68L, ClusterStagingLayout.endOffset(0L, mesh, false));
    }

    private static CpuSectionMesh mesh(int opaque, int cutout, int transmissive) {
        int triangles = opaque + cutout + transmissive;
        return new CpuSectionMesh(
                new float[triangles * 9],
                new int[triangles * CpuSectionMesh.PRIMITIVE_WORDS],
                new int[0],
                TriangleLayout.triangles(opaque, cutout, transmissive),
                OpacityMicromapData.fullyUnknown(cutout),
                CpuSectionLights.EMPTY);
    }
}
