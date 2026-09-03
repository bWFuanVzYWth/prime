package dev.prime.render.terrain;

/**
 * Logically immutable section mesh.
 *
 * <p>Construction transfers exclusive ownership of both primitive arrays to this value. Accessors
 * expose borrowed read-only storage for zero-copy cluster assembly and upload; callers must never
 * mutate it. Java has no zero-copy read-only primitive-array view, and cloning here would duplicate
 * the dominant terrain payload.
 */
public record CpuSectionMesh(
        CpuMeshSegment geometry,
        OpacityMicromapData opacityMicromap,
        CpuSectionLights lights) {

    public static final int PRIMITIVE_WORDS = 8;
    public static final int SURFACE_RELATION_KIND_MASK = 0xf;
    public static final int SURFACE_RELATION_BOUNDARY = 1;
    public static final int SURFACE_RELATION_OVERLAY = 2;
    public static final int SURFACE_RELATION_BILATERAL = 3;
    public static final int SURFACE_RELATION_MICRO_GAP_ELIGIBLE = 1 << 4;
    public static final int SURFACE_RELATION_POSITIVE_ONLY = 1 << 4;

    public CpuSectionMesh(
            float[] positions,
            int[] primitiveRecords,
            int[] surfaceRelationRecords,
            TriangleLayout triangleLayout,
            OpacityMicromapData opacityMicromap,
            CpuSectionLights lights) {
        this(
                new CpuMeshSegment(
                        positions, primitiveRecords, surfaceRelationRecords, triangleLayout),
                opacityMicromap,
                lights);
    }

    public CpuSectionMesh {
        java.util.Objects.requireNonNull(geometry, "geometry");
        java.util.Objects.requireNonNull(opacityMicromap, "opacityMicromap");
        java.util.Objects.requireNonNull(lights, "lights");
        if (opacityMicromap.triangleCount() != geometry.triangleLayout().cutoutTriangleCount()) {
            throw new IllegalArgumentException("Opacity micromap does not match cutout geometry");
        }
    }

    public boolean isEmpty() {
        return this.geometry.triangleCount() == 0;
    }

    public long byteSize() {
        return (long) this.geometry.positions().length * Float.BYTES
                + (long) this.geometry.primitiveRecords().length * Integer.BYTES
                + (long) this.geometry.surfaceRelationRecords().length * Integer.BYTES
                + this.opacityMicromap.byteSize()
                + this.lights.byteSize();
    }

}
