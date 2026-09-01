package dev.prime.render.terrain;

import java.util.Objects;
import java.util.function.IntUnaryOperator;

/** Rewrites source RGBA8 tint samples to renderer-lifetime exact TintIds before upload. */
public final class TintIdResolver {
    public static final int MAX_TINT_ID = 0xffff;

    private TintIdResolver() {
    }

    public static int[] primitiveRecords(
            int[] resolvedRecords,
            int[] sourceRecords,
            IntUnaryOperator resolver) {
        Objects.requireNonNull(resolvedRecords, "resolvedRecords");
        Objects.requireNonNull(sourceRecords, "sourceRecords");
        Objects.requireNonNull(resolver, "resolver");
        if (resolvedRecords.length != sourceRecords.length
                || sourceRecords.length % CpuSectionMesh.PRIMITIVE_WORDS != 0) {
            throw new IllegalArgumentException(
                    "Primitive tint remap requires matching complete records");
        }
        int[] result = null;
        for (int record = 0; record < resolvedRecords.length;
                record += CpuSectionMesh.PRIMITIVE_WORDS) {
            if ((sourceRecords[record + 5] & PrimitivePacking.DYNAMIC_TEXTURE_FLAG) != 0) {
                continue;
            }
            if (result == null) {
                result = resolvedRecords.clone();
            }
            int sourceIdentity = sourceRecords[record + PrimitivePacking.MEDIUM_ID_WORD];
            int packedRgba = sourceRecords[record + 3] & 0x00ff_ffff
                    | PrimitivePacking.unpackSourceTintAlpha(sourceIdentity);
            int tintId = resolvePackedRgba(packedRgba, resolver);
            int identity = resolvedRecords[record + PrimitivePacking.MEDIUM_ID_WORD];
            int materialId = MaterialIdResolver.unpackMaterialId(identity);
            if (materialId == 0) {
                // Dynamic and constant/baked records retain their inline compatibility ABI.
                result[record + 3] = replaceTint(resolvedRecords[record + 3], tintId);
            } else {
                // Table-backed GPU records use the low identity lane for exact TintId; MediumId
                // comes from MaterialId core. Only tangent handedness remains in the tint word.
                result[record + 3] = resolvedRecords[record + 3] & 0xff00_0000;
                result[record + PrimitivePacking.MEDIUM_ID_WORD] =
                        MaterialIdResolver.pack(tintId, materialId);
            }
        }
        return result == null ? resolvedRecords : result;
    }

    public static int[] surfaceRelations(
            int[] resolvedRelations,
            int[] sourceRelations,
            int primitiveCount,
            IntUnaryOperator resolver) {
        Objects.requireNonNull(resolvedRelations, "resolvedRelations");
        Objects.requireNonNull(sourceRelations, "sourceRelations");
        Objects.requireNonNull(resolver, "resolver");
        if (resolvedRelations.length != sourceRelations.length) {
            throw new IllegalArgumentException(
                    "TintId packing requires matching surface-relation tables");
        }
        if (resolvedRelations.length == 0) {
            return resolvedRelations;
        }
        SurfaceRelationTable.validate(sourceRelations, primitiveCount);
        int[] result = resolvedRelations.clone();
        int cursor = primitiveCount;
        while (cursor < result.length) {
            int kind = sourceRelations[cursor] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
            int tintWord = kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                    ? cursor + 2
                    : cursor + 1 + 3;
            int packedRgba = sourceRelations[tintWord] & 0x00ff_ffff;
            if (kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY) {
                packedRgba |= sourceRelations[tintWord] & 0xff00_0000;
            } else {
                packedRgba |= PrimitivePacking.unpackSourceTintAlpha(
                        sourceRelations[cursor + 1 + PrimitivePacking.MEDIUM_ID_WORD]);
            }
            int tintId = resolvePackedRgba(packedRgba, resolver);
            result[tintWord] = kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                    ? tintId
                    : replaceTint(result[tintWord], tintId);
            cursor += SurfaceRelationTable.wordsForControl(sourceRelations[cursor]);
        }
        return result;
    }

    public static int resolvePackedRgba(int packedRgba, IntUnaryOperator resolver) {
        Objects.requireNonNull(resolver, "resolver");
        return requireTintId(resolver.applyAsInt(packedRgba));
    }

    public static int resolveOpaquePackedRgb(int packedRgb, IntUnaryOperator resolver) {
        if ((packedRgb & 0xff00_0000) != 0) {
            throw new IllegalArgumentException("Packed tint exceeds RGB8");
        }
        return resolvePackedRgba(packedRgb | 0xff00_0000, resolver);
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
