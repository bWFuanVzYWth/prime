// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import java.util.Objects;

/** Ownership-transferred, borrowed read-only triangle storage for one bounded upload segment. */
public record CpuMeshSegment(
        float[] positions,
        int[] primitiveRecords,
        int[] surfaceRelationRecords,
        TriangleLayout triangleLayout) {
    public CpuMeshSegment {
        positions = Objects.requireNonNull(positions, "positions");
        primitiveRecords = Objects.requireNonNull(primitiveRecords, "primitiveRecords");
        surfaceRelationRecords = Objects.requireNonNull(
                surfaceRelationRecords, "surfaceRelationRecords");
        triangleLayout = Objects.requireNonNull(triangleLayout, "triangleLayout");
        int triangleCount = Math.toIntExact(triangleLayout.triangleCount());
        int primitiveCount = Math.toIntExact(triangleLayout.primitiveCount());
        if (positions.length != Math.multiplyExact(triangleCount, 9)
                || primitiveRecords.length != Math.multiplyExact(
                        primitiveCount, CpuSectionMesh.PRIMITIVE_WORDS)) {
            throw new IllegalArgumentException("Invalid CPU mesh segment");
        }
        SurfaceRelationTable.validate(surfaceRelationRecords, primitiveCount);
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

    public long positionBytes() {
        return (long) this.positions.length * Float.BYTES;
    }

    public long primitiveBytes() {
        return (long) this.primitiveRecords.length * Integer.BYTES;
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
}
