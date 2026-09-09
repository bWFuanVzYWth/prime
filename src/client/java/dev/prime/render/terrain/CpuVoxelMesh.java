// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import java.util.Arrays;
import java.util.Objects;

/**
 * One immutable auxiliary BLAS payload.
 *
 * <p>All instances of this value share the same immutable position and primitive storage. Per-face
 * translation and tint remain in {@link CpuVoxelInstances}; dynamic unique fallbacks use the same
 * payload shape while explicitly disabling content sharing.
 */
public final class CpuVoxelMesh {
    private final CpuMeshSegment geometry;
    private final OpacityMicromapData opacityMicromap;
    private final boolean reusable;
    private final int gpuContentHash;

    public CpuVoxelMesh(
            float[] positions,
            int[] primitiveRecords,
            TriangleLayout triangleLayout,
            OpacityMicromapData opacityMicromap) {
        this(positions, primitiveRecords, triangleLayout, opacityMicromap, true);
    }

    private CpuVoxelMesh(
            float[] positions,
            int[] primitiveRecords,
            TriangleLayout triangleLayout,
            OpacityMicromapData opacityMicromap,
            boolean reusable) {
        this.geometry = new CpuMeshSegment(
                positions, primitiveRecords, new int[0], triangleLayout);
        this.opacityMicromap = Objects.requireNonNull(
                opacityMicromap, "opacityMicromap");
        this.reusable = reusable;
        if (opacityMicromap.triangleCount() != this.geometry.cutoutTriangleCount()) {
            throw new IllegalArgumentException("Invalid instanced BLAS payload");
        }
        if (this.geometry.triangleCount() == 0) {
            throw new IllegalArgumentException(
                    "An instanced BLAS payload must contain geometry");
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

    /** Creates one exact transient payload that must not share BLAS ownership by content. */
    public static CpuVoxelMesh unique(
            float[] positions,
            int[] primitiveRecords,
            TriangleLayout triangleLayout,
            OpacityMicromapData opacityMicromap) {
        return new CpuVoxelMesh(
                positions, primitiveRecords, triangleLayout, opacityMicromap, false);
    }

    public CpuMeshSegment geometry() {
        return this.geometry;
    }

    public OpacityMicromapData opacityMicromap() {
        return this.opacityMicromap;
    }

    public boolean reusable() {
        return this.reusable;
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
