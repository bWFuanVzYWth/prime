package dev.prime.render.vulkan.terrain;

import dev.prime.render.terrain.CpuClusterMesh;
import dev.prime.render.terrain.CpuSectionMesh;
import dev.prime.render.terrain.CpuVoxelMesh;
import dev.prime.render.terrain.GpuSurfaceRelationTable;
import dev.prime.render.terrain.OpacityMicromapData;
import dev.prime.render.vulkan.StagingArena;
import org.lwjgl.vulkan.VkMicromapTriangleEXT;

/** One staging layout contract shared by runtime admission and Vulkan terrain upload. */
public final class ClusterStagingLayout {
    private ClusterStagingLayout() {}

    public static long endOffset(
            long cursor, CpuClusterMesh mesh, boolean includeOpacityMicromap) {
        if (mesh.isEmpty()) {
            return cursor;
        }
        long result = cursor;
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            result = segmentEndOffset(result, segment.opaqueTriangleCount(), segment.opaquePrimitiveCount());
            result = segmentEndOffset(result, segment.cutoutTriangleCount(), segment.cutoutPrimitiveCount());
            result = segmentEndOffset(result, segment.transmissiveTriangleCount(), segment.transmissivePrimitiveCount());
        }
        long surfaceRelationBytes = GpuSurfaceRelationTable.byteSize(mesh);
        if (surfaceRelationBytes != 0L) {
            result = StagingArena.requiredEndOffset(
                    result, surfaceRelationBytes, Integer.BYTES);
        }
        result = opacityEndOffset(result, mesh.opacityMicromap(), includeOpacityMicromap);
        if (!mesh.lights().isEmpty()) {
            result = StagingArena.requiredEndOffset(result, mesh.lights().byteSize(), 16L);
        }
        for (CpuVoxelMesh voxelMesh : mesh.voxelMeshes()) {
            result = StagingArena.requiredEndOffset(result, voxelMesh.positionBytes(), Float.BYTES);
            result = StagingArena.requiredEndOffset(result, voxelMesh.primitiveBytes(), Integer.BYTES);
            result = opacityEndOffset(result, voxelMesh.opacityMicromap(), includeOpacityMicromap);
        }
        return result;
    }

    public static long endOffset(
            long cursor,
            long positionBytes,
            long primitiveBytes,
            long lightBytes,
            long opacityIndexBytes,
            long opacityDataBytes,
            long opacityTriangleBytes) {
        long endOffset = StagingArena.requiredEndOffset(cursor, positionBytes, Float.BYTES);
        endOffset = StagingArena.requiredEndOffset(endOffset, primitiveBytes, Integer.BYTES);
        if (lightBytes != 0L) {
            endOffset = StagingArena.requiredEndOffset(endOffset, lightBytes, 16L);
        }
        if (opacityIndexBytes != 0L) {
            endOffset = StagingArena.requiredEndOffset(endOffset, opacityIndexBytes, Integer.BYTES);
        }
        if (opacityDataBytes != 0L) {
            endOffset = StagingArena.requiredEndOffset(endOffset, opacityDataBytes, 16L);
        }
        return opacityTriangleBytes == 0L
                ? endOffset
                : StagingArena.requiredEndOffset(endOffset, opacityTriangleBytes, Integer.BYTES);
    }

    private static long opacityEndOffset(
            long cursor, OpacityMicromapData micromap, boolean include) {
        return endOffset(
                cursor,
                0L,
                0L,
                0L,
                include ? (long) micromap.triangleCount() * Integer.BYTES : 0L,
                include ? micromap.blockStorageBytes() : 0L,
                include ? (long) micromap.blockCount() * VkMicromapTriangleEXT.SIZEOF : 0L);
    }

    private static long segmentEndOffset(long cursor, int triangles, int primitives) {
        if (triangles == 0) {
            return cursor;
        }
        long result = StagingArena.requiredEndOffset(
                cursor, (long) triangles * 9L * Float.BYTES, Float.BYTES);
        return StagingArena.requiredEndOffset(
                result,
                (long) primitives * CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES,
                Integer.BYTES);
    }
}
