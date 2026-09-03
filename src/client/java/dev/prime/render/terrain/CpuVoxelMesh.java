package dev.prime.render.terrain;

import java.util.Arrays;
import java.util.Objects;

/**
 * One reusable texture-derived height-field BLAS.
 *
 * <p>All instances of this value share the same immutable position and primitive storage. Per-face
 * translation and tint remain in {@link CpuVoxelInstances}, so biome colors do not duplicate a
 * high-detail mesh.
 */
public final class CpuVoxelMesh {
    private final CpuMeshSegment geometry;
    private final OpacityMicromapData opacityMicromap;
    private final int gpuContentHash;

    public CpuVoxelMesh(
            float[] positions,
            int[] primitiveRecords,
            TriangleLayout triangleLayout,
            OpacityMicromapData opacityMicromap) {
        this.geometry = new CpuMeshSegment(
                positions, primitiveRecords, new int[0], triangleLayout);
        this.opacityMicromap = Objects.requireNonNull(
                opacityMicromap, "opacityMicromap");
        if (opacityMicromap.triangleCount() != this.geometry.cutoutTriangleCount()) {
            throw new IllegalArgumentException("Invalid reusable voxel-surface mesh");
        }
        if (this.geometry.triangleCount() == 0) {
            throw new IllegalArgumentException(
                    "A reusable voxel-surface mesh must contain geometry");
        }
        int hash = rawFloatHash(this.geometry.positions());
        hash = 31 * hash + Arrays.hashCode(this.geometry.primitiveRecords());
        hash = 31 * hash + this.geometry.opaqueTriangleCount();
        hash = 31 * hash + this.geometry.cutoutTriangleCount();
        hash = 31 * hash + this.geometry.transmissiveTriangleCount();
        hash = 31 * hash + Arrays.hashCode(this.opacityMicromap.blocks());
        hash = 31 * hash + Arrays.hashCode(this.opacityMicromap.blockOffsets());
        hash = 31 * hash + Arrays.hashCode(this.opacityMicromap.blockFormats());
        hash = 31 * hash + Arrays.hashCode(
                this.opacityMicromap.blockSubdivisionLevels());
        hash = 31 * hash + Arrays.hashCode(this.opacityMicromap.triangleIndices());
        this.gpuContentHash = hash;
    }

    public CpuMeshSegment geometry() {
        return this.geometry;
    }

    public OpacityMicromapData opacityMicromap() {
        return this.opacityMicromap;
    }

    public long byteSize() {
        return Math.addExact(
                Math.addExact(this.geometry.positionBytes(), this.geometry.primitiveBytes()),
                this.opacityMicromap.byteSize());
    }

    /** Stable fingerprint of the borrowed read-only GPU payload. */
    public int gpuContentHash() {
        return this.gpuContentHash;
    }

    private static int rawFloatHash(float[] values) {
        int result = 1;
        for (float value : values) {
            result = 31 * result + Float.floatToRawIntBits(value);
        }
        return result;
    }
}
