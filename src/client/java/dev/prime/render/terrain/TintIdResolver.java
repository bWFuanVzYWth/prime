package dev.prime.render.terrain;

import java.util.Objects;
import java.util.function.IntUnaryOperator;

/** Rewrites source RGB8 tint identities to renderer-lifetime exact TintIds before upload. */
public final class TintIdResolver {
    public static final int MAX_TINT_ID = 0xffff;

    private TintIdResolver() {
    }

    public static int[] primitiveRecords(int[] source, IntUnaryOperator resolver) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(resolver, "resolver");
        if (source.length % CpuSectionMesh.PRIMITIVE_WORDS != 0) {
            throw new IllegalArgumentException("Primitive tint remap requires complete records");
        }
        int[] result = null;
        for (int record = 0; record < source.length;
                record += CpuSectionMesh.PRIMITIVE_WORDS) {
            if ((source[record + 5] & PrimitivePacking.DYNAMIC_TEXTURE_FLAG) != 0) {
                continue;
            }
            if (result == null) {
                result = source.clone();
            }
            result[record + 3] = replaceTint(
                    source[record + 3], resolver.applyAsInt(source[record + 3] & 0x00ff_ffff));
        }
        return result == null ? source : result;
    }

    public static int[] surfaceRelations(
            int[] source, int primitiveCount, IntUnaryOperator resolver) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(resolver, "resolver");
        if (source.length == 0) {
            return source;
        }
        SurfaceRelationTable.validate(source, primitiveCount);
        int[] result = source.clone();
        int cursor = primitiveCount;
        while (cursor < result.length) {
            int kind = result[cursor] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
            int tintWord = kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                    ? cursor + 2
                    : cursor + 1 + 3;
            result[tintWord] = replaceTint(
                    result[tintWord],
                    resolver.applyAsInt(result[tintWord] & 0x00ff_ffff));
            cursor += SurfaceRelationTable.wordsForControl(result[cursor]);
        }
        return result;
    }

    public static int resolvePackedRgb(int packedRgb, IntUnaryOperator resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if ((packedRgb & 0xff00_0000) != 0) {
            throw new IllegalArgumentException("Packed tint exceeds RGB8");
        }
        return requireTintId(resolver.applyAsInt(packedRgb));
    }

    private static int replaceTint(int packedControlTint, int tintId) {
        return packedControlTint & 0xff00_0000 | requireTintId(tintId);
    }

    private static int requireTintId(int tintId) {
        if (tintId < 0 || tintId > MAX_TINT_ID) {
            throw new IllegalArgumentException("TintId exceeds its exact 16-bit physical encoding");
        }
        return tintId;
    }
}
