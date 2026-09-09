// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

/** Triangle partitions and paired-triangle macro tails for one three-geometry BLAS. */
public record TriangleLayout(
        long opaqueTriangleCount,
        long cutoutTriangleCount,
        long transmissiveTriangleCount,
        long opaqueMacroTriangleCount,
        long cutoutMacroTriangleCount,
        long transmissiveMacroTriangleCount) {
    public TriangleLayout {
        requireValidMacroCount(opaqueTriangleCount, opaqueMacroTriangleCount);
        requireValidMacroCount(cutoutTriangleCount, cutoutMacroTriangleCount);
        requireValidMacroCount(transmissiveTriangleCount, transmissiveMacroTriangleCount);
    }

    public static TriangleLayout triangles(
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount) {
        return new TriangleLayout(
                opaqueTriangleCount,
                cutoutTriangleCount,
                transmissiveTriangleCount,
                0L,
                0L,
                0L);
    }

    public TriangleLayout plus(TriangleLayout other) {
        return new TriangleLayout(
                Math.addExact(this.opaqueTriangleCount, other.opaqueTriangleCount),
                Math.addExact(this.cutoutTriangleCount, other.cutoutTriangleCount),
                Math.addExact(this.transmissiveTriangleCount, other.transmissiveTriangleCount),
                Math.addExact(this.opaqueMacroTriangleCount, other.opaqueMacroTriangleCount),
                Math.addExact(this.cutoutMacroTriangleCount, other.cutoutMacroTriangleCount),
                Math.addExact(
                        this.transmissiveMacroTriangleCount,
                        other.transmissiveMacroTriangleCount));
    }

    public long triangleCount() {
        return Math.addExact(
                Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                this.transmissiveTriangleCount);
    }

    public long opaquePrimitiveCount() {
        return primitiveCount(this.opaqueTriangleCount, this.opaqueMacroTriangleCount);
    }

    public long cutoutPrimitiveCount() {
        return primitiveCount(this.cutoutTriangleCount, this.cutoutMacroTriangleCount);
    }

    public long transmissivePrimitiveCount() {
        return primitiveCount(
                this.transmissiveTriangleCount, this.transmissiveMacroTriangleCount);
    }

    public long primitiveCount() {
        return Math.addExact(
                Math.addExact(this.opaquePrimitiveCount(), this.cutoutPrimitiveCount()),
                this.transmissivePrimitiveCount());
    }

    public long cutoutPrimitiveBase() {
        return this.opaquePrimitiveCount();
    }

    public long transmissivePrimitiveBase() {
        return Math.addExact(this.opaquePrimitiveCount(), this.cutoutPrimitiveCount());
    }

    public long opaqueMacroTriangleBase() {
        return this.opaqueTriangleCount - this.opaqueMacroTriangleCount;
    }

    public long cutoutMacroTriangleBase() {
        return this.cutoutTriangleCount - this.cutoutMacroTriangleCount;
    }

    public long transmissiveMacroTriangleBase() {
        return this.transmissiveTriangleCount - this.transmissiveMacroTriangleCount;
    }

    private static long primitiveCount(long triangleCount, long macroTriangleCount) {
        return Math.subtractExact(triangleCount, macroTriangleCount / 2L);
    }

    private static void requireValidMacroCount(long triangleCount, long macroTriangleCount) {
        if (triangleCount < 0L
                || macroTriangleCount < 0L
                || macroTriangleCount > triangleCount
                || (macroTriangleCount & 1L) != 0L) {
            throw new IllegalArgumentException(
                    "Macro triangle counts must be even and inside their geometry partition");
        }
    }
}
