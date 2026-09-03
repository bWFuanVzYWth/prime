package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TerrainMemoryBudgetTest {
    @Test
    void workerCountUsesPartitionWorkingSetWithoutDefiningTotalGeometryCapacity() {
        long partPeak = TerrainMemoryBudget.TARGET_SEGMENT_BYTES * 3L;
        assertEquals(1, TerrainMemoryBudget.maximumInFlight(16, partPeak * 3L));
        assertEquals(2, TerrainMemoryBudget.maximumInFlight(16, partPeak * 8L));
        assertEquals(4, TerrainMemoryBudget.maximumInFlight(4, partPeak * 16L));
    }

    @Test
    void cpuSegmentsRespectDeviceLimitsAndKeepWholeQuads() {
        assertEquals(
                TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                TerrainMemoryBudget.segmentTriangleTarget(-1L));
        assertEquals(10, TerrainMemoryBudget.segmentTriangleTarget(11L));
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainMemoryBudget.segmentTriangleTarget(1L));
    }

    @Test
    void segmentTargetStartsAnotherSegmentInsteadOfRejectingGeometry() {
        CpuSectionMesh mesh = new CpuSectionMesh(
                new float[9],
                new int[CpuSectionMesh.PRIMITIVE_WORDS],
                new int[0],
                TriangleLayout.triangles(1, 0, 0),
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
        assertFalse(TerrainMemoryBudget.startsNewSegment(
                0L, 0, mesh, TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES));
        assertTrue(TerrainMemoryBudget.startsNewSegment(
                TerrainMemoryBudget.TARGET_SEGMENT_BYTES,
                1,
                mesh,
                TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES));
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainMemoryBudget.startsNewSegment(
                        -1L, 0, mesh, TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES));
    }
}
