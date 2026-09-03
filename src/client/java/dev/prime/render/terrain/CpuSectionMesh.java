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
        int opaqueTriangleCount,
        int cutoutTriangleCount,
        int transmissiveTriangleCount,
        int opaqueMacroTriangleCount,
        int cutoutMacroTriangleCount,
        int transmissiveMacroTriangleCount,
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
        opacityMicromap = Objects.requireNonNull(
                opacityMicromap, "opacityMicromap");
        lights = Objects.requireNonNull(lights, "lights");
        if (opaqueTriangleCount < 0 || cutoutTriangleCount < 0 || transmissiveTriangleCount < 0) {
            throw new IllegalArgumentException("Triangle counts must not be negative");
        }
        requireValidMacroCount(opaqueTriangleCount, opaqueMacroTriangleCount);
        requireValidMacroCount(cutoutTriangleCount, cutoutMacroTriangleCount);
        requireValidMacroCount(transmissiveTriangleCount, transmissiveMacroTriangleCount);
        int triangleCount = Math.addExact(
                Math.addExact(opaqueTriangleCount, cutoutTriangleCount),
                transmissiveTriangleCount);
        if (positions.length != Math.multiplyExact(triangleCount, 9)) {
            throw new IllegalArgumentException("Position array does not match triangle count");
        }
        int primitiveCount = Math.addExact(
                Math.addExact(
                        primitiveCount(opaqueTriangleCount, opaqueMacroTriangleCount),
                        primitiveCount(cutoutTriangleCount, cutoutMacroTriangleCount)),
                primitiveCount(transmissiveTriangleCount, transmissiveMacroTriangleCount));
        if (primitiveRecords.length != Math.multiplyExact(primitiveCount, PRIMITIVE_WORDS)) {
            throw new IllegalArgumentException("Primitive array does not match triangle count");
        }
        SurfaceRelationTable.validate(surfaceRelationRecords, primitiveCount);
        if (opacityMicromap.triangleCount() != cutoutTriangleCount) {
            throw new IllegalArgumentException("Opacity micromap does not match cutout geometry");
        }
    }

    public CpuSectionMesh(
            float[] positions,
            int[] primitiveRecords,
            int[] surfaceRelationRecords,
            int opaqueTriangleCount,
            int cutoutTriangleCount,
            int transmissiveTriangleCount,
            OpacityMicromapData opacityMicromap,
            CpuSectionLights lights) {
        this(
                positions,
                primitiveRecords,
                surfaceRelationRecords,
                opaqueTriangleCount,
                cutoutTriangleCount,
                transmissiveTriangleCount,
                0,
                0,
                0,
                opacityMicromap,
                lights);
    }

    public CpuSectionMesh(
            float[] positions,
            int[] primitiveRecords,
            int opaqueTriangleCount,
            int cutoutTriangleCount,
            int transmissiveTriangleCount,
            OpacityMicromapData opacityMicromap,
            CpuSectionLights lights) {
        this(
                positions,
                primitiveRecords,
                new int[0],
                opaqueTriangleCount,
                cutoutTriangleCount,
                transmissiveTriangleCount,
                0,
                0,
                0,
                opacityMicromap,
                lights);
    }

    public boolean isEmpty() {
        return this.triangleCount() == 0;
    }

    public int triangleCount() {
        return Math.addExact(
                Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                this.transmissiveTriangleCount);
    }

    public int opaquePrimitiveCount() {
        return primitiveCount(this.opaqueTriangleCount, this.opaqueMacroTriangleCount);
    }

    public int cutoutPrimitiveCount() {
        return primitiveCount(this.cutoutTriangleCount, this.cutoutMacroTriangleCount);
    }

    public int transmissivePrimitiveCount() {
        return primitiveCount(
                this.transmissiveTriangleCount, this.transmissiveMacroTriangleCount);
    }

    public int primitiveCount() {
        return Math.addExact(
                Math.addExact(this.opaquePrimitiveCount(), this.cutoutPrimitiveCount()),
                this.transmissivePrimitiveCount());
    }

    public int opaqueMacroTriangleBase() {
        return this.opaqueTriangleCount - this.opaqueMacroTriangleCount;
    }

    public int cutoutMacroTriangleBase() {
        return this.cutoutTriangleCount - this.cutoutMacroTriangleCount;
    }

    public int transmissiveMacroTriangleBase() {
        return this.transmissiveTriangleCount - this.transmissiveMacroTriangleCount;
    }

    public long byteSize() {
        return (long) this.positions.length * Float.BYTES
                + (long) this.primitiveRecords.length * Integer.BYTES
                + (long) this.surfaceRelationRecords.length * Integer.BYTES
                + this.opacityMicromap.byteSize()
                + this.lights.byteSize();
    }

    static int primitiveCount(int triangleCount, int macroTriangleCount) {
        return Math.subtractExact(triangleCount, macroTriangleCount / 2);
    }

    private static void requireValidMacroCount(int triangleCount, int macroTriangleCount) {
        if (macroTriangleCount < 0
                || macroTriangleCount > triangleCount
                || (macroTriangleCount & 1) != 0) {
            throw new IllegalArgumentException(
                    "Macro triangle counts must be even and inside their geometry partition");
        }
    }
}
