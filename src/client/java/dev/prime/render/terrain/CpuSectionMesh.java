package dev.prime.render.terrain;

import java.util.Objects;

/**
 * Logically immutable section mesh.
 *
 * <p>Construction transfers exclusive ownership of both primitive arrays to this value. Accessors
 * expose borrowed read-only storage for zero-copy cluster assembly and upload; callers must never
 * mutate it. Java has no zero-copy read-only primitive-array view, and cloning here would duplicate
 * the dominant terrain payload.
 */
public record CpuSectionMesh(
        float[] positions,
        int[] primitiveRecords,
        int[] surfaceRelationRecords,
        TriangleLayout triangleLayout,
        OpacityMicromapData opacityMicromap,
        CpuSectionLights lights) {

    public static final int PRIMITIVE_WORDS = 8;
    public static final int SURFACE_RELATION_KIND_MASK = 0xf;
    public static final int SURFACE_RELATION_BOUNDARY = 1;
    public static final int SURFACE_RELATION_OVERLAY = 2;
    public static final int SURFACE_RELATION_BILATERAL = 3;
    public static final int SURFACE_RELATION_MICRO_GAP_ELIGIBLE = 1 << 4;
    public static final int SURFACE_RELATION_POSITIVE_ONLY = 1 << 4;

    public CpuSectionMesh {
        positions = Objects.requireNonNull(positions, "positions");
        primitiveRecords = Objects.requireNonNull(
                primitiveRecords, "primitiveRecords");
        surfaceRelationRecords = Objects.requireNonNull(
                surfaceRelationRecords, "surfaceRelationRecords");
        triangleLayout = Objects.requireNonNull(triangleLayout, "triangleLayout");
        opacityMicromap = Objects.requireNonNull(
                opacityMicromap, "opacityMicromap");
        lights = Objects.requireNonNull(lights, "lights");
        int triangleCount = Math.toIntExact(triangleLayout.triangleCount());
        if (positions.length != Math.multiplyExact(triangleCount, 9)) {
            throw new IllegalArgumentException("Position array does not match triangle count");
        }
        int primitiveCount = Math.toIntExact(triangleLayout.primitiveCount());
        if (primitiveRecords.length != Math.multiplyExact(primitiveCount, PRIMITIVE_WORDS)) {
            throw new IllegalArgumentException("Primitive array does not match triangle count");
        }
        SurfaceRelationTable.validate(surfaceRelationRecords, primitiveCount);
        if (opacityMicromap.triangleCount() != triangleLayout.cutoutTriangleCount()) {
            throw new IllegalArgumentException("Opacity micromap does not match cutout geometry");
        }
    }

    public boolean isEmpty() {
        return this.triangleCount() == 0;
    }

    public int triangleCount() {
        return Math.toIntExact(this.triangleLayout.triangleCount());
    }

    public int opaqueTriangleCount() {
        return Math.toIntExact(this.triangleLayout.opaqueTriangleCount());
    }

    public int cutoutTriangleCount() {
        return Math.toIntExact(this.triangleLayout.cutoutTriangleCount());
    }

    public int transmissiveTriangleCount() {
        return Math.toIntExact(this.triangleLayout.transmissiveTriangleCount());
    }

    public int opaqueMacroTriangleCount() {
        return Math.toIntExact(this.triangleLayout.opaqueMacroTriangleCount());
    }

    public int cutoutMacroTriangleCount() {
        return Math.toIntExact(this.triangleLayout.cutoutMacroTriangleCount());
    }

    public int transmissiveMacroTriangleCount() {
        return Math.toIntExact(this.triangleLayout.transmissiveMacroTriangleCount());
    }

    public int opaquePrimitiveCount() {
        return Math.toIntExact(this.triangleLayout.opaquePrimitiveCount());
    }

    public int cutoutPrimitiveCount() {
        return Math.toIntExact(this.triangleLayout.cutoutPrimitiveCount());
    }

    public int transmissivePrimitiveCount() {
        return Math.toIntExact(this.triangleLayout.transmissivePrimitiveCount());
    }

    public int primitiveCount() {
        return Math.toIntExact(this.triangleLayout.primitiveCount());
    }

    public int opaqueMacroTriangleBase() {
        return Math.toIntExact(this.triangleLayout.opaqueMacroTriangleBase());
    }

    public int cutoutMacroTriangleBase() {
        return Math.toIntExact(this.triangleLayout.cutoutMacroTriangleBase());
    }

    public int transmissiveMacroTriangleBase() {
        return Math.toIntExact(this.triangleLayout.transmissiveMacroTriangleBase());
    }

    public long byteSize() {
        return (long) this.positions.length * Float.BYTES
                + (long) this.primitiveRecords.length * Integer.BYTES
                + (long) this.surfaceRelationRecords.length * Integer.BYTES
                + this.opacityMicromap.byteSize()
                + this.lights.byteSize();
    }

}
