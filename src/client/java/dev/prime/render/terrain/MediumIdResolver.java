package dev.prime.render.terrain;

import java.util.Objects;

/** Rewrites cluster-local medium IDs to renderer-lifetime IDs before upload. */
public final class MediumIdResolver {
    private MediumIdResolver() {
    }

    public static int[] primitiveRecords(int[] source, int[] localToRenderer) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(localToRenderer, "localToRenderer");
        if (source.length == 0) {
            return source;
        }
        boolean any = false;
        for (int record = 0; record < source.length;
                record += CpuSectionMesh.PRIMITIVE_WORDS) {
            int localId = source[record + PrimitivePacking.MEDIUM_ID_WORD];
            int flags = PrimitivePacking.unpackControl(
                    source[record + 3], source[record + 5]);
            requireMediumUse(localId, flags);
            resolve(localId, localToRenderer);
            any |= localId != 0;
        }
        if (!any) {
            return source;
        }
        int[] result = source.clone();
        for (int record = 0; record < result.length;
                record += CpuSectionMesh.PRIMITIVE_WORDS) {
            result[record + PrimitivePacking.MEDIUM_ID_WORD] = resolve(
                    result[record + PrimitivePacking.MEDIUM_ID_WORD], localToRenderer);
        }
        return result;
    }

    public static int[] surfaceRelations(
            int[] source,
            int primitiveCount,
            int[] localToRenderer) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(localToRenderer, "localToRenderer");
        if (source.length == 0) {
            return source;
        }
        int[] result = source.clone();
        boolean any = false;
        int cursor = primitiveCount;
        while (cursor < result.length) {
            int kind = result[cursor] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
            int mediumWord = kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                    ? cursor + 4
                    : cursor + 1 + PrimitivePacking.MEDIUM_ID_WORD;
            int localId = result[mediumWord];
            if (kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY) {
                if (localId == 0) {
                    throw new IllegalArgumentException(
                            "Boundary relation must name its adjacent medium");
                }
            } else {
                int material = cursor + 1;
                int flags = PrimitivePacking.unpackControl(
                        result[material + 3], result[material + 5]);
                requireMediumUse(localId, flags);
            }
            result[mediumWord] = resolve(localId, localToRenderer);
            any |= localId != 0;
            cursor += SurfaceRelationTable.wordsForControl(result[cursor]);
        }
        return any ? result : source;
    }

    private static int resolve(int localId, int[] localToRenderer) {
        if (localToRenderer.length == 0 || localToRenderer[0] != 0) {
            throw new IllegalArgumentException(
                    "Medium remap must reserve zero for vacuum");
        }
        if (localId < 0 || localId >= localToRenderer.length) {
            throw new IllegalArgumentException(
                    "Primitive references a medium outside its local catalog");
        }
        int resolved = localToRenderer[localId];
        if (localId != 0 && resolved == 0) {
            throw new IllegalArgumentException(
                    "Non-vacuum medium maps to the reserved vacuum identity");
        }
        return resolved;
    }

    private static void requireMediumUse(int mediumId, int flags) {
        boolean solidMedium = PrimitivePacking.isTransmissive(flags)
                && !PrimitivePacking.isThinWalled(flags);
        if (solidMedium != (mediumId != 0)) {
            throw new IllegalArgumentException(
                    "Primitive MediumId disagrees with its transmissive topology");
        }
    }
}
