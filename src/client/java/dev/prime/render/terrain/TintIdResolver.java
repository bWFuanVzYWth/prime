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
            int tintId = requireTintId(
                    resolver.applyAsInt(source[record + 3] & 0x00ff_ffff));
            int identity = source[record + PrimitivePacking.MEDIUM_ID_WORD];
            int materialId = MaterialIdResolver.unpackMaterialId(identity);
            if (materialId == 0) {
                // Dynamic and constant/baked records retain their inline compatibility ABI.
                result[record + 3] = replaceTint(source[record + 3], tintId);
            } else {
                // Table-backed GPU records use the low identity lane for exact TintId; MediumId
                // comes from MaterialId core. Only tangent handedness remains in the tint word.
                result[record + 3] = source[record + 3] & 0xff00_0000;
                result[record + PrimitivePacking.MEDIUM_ID_WORD] =
                        MaterialIdResolver.pack(tintId, materialId);
            }
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
            int tintId = requireTintId(
                    resolver.applyAsInt(result[tintWord] & 0x00ff_ffff));
            // Boundary tint is a pure color fact and may carry a non-semantic source alpha byte;
            // embedded material tint shares its high byte with validated recipe control.
            result[tintWord] = kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                    ? tintId
                    : replaceTint(result[tintWord], tintId);
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
