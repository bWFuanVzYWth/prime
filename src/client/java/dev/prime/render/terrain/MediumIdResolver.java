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
            int sourceIdentity = source[record + PrimitivePacking.MEDIUM_ID_WORD];
            int localId = PrimitivePacking.unpackSourceMediumId(sourceIdentity);
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
            int sourceIdentity = result[record + PrimitivePacking.MEDIUM_ID_WORD];
            result[record + PrimitivePacking.MEDIUM_ID_WORD] =
                    PrimitivePacking.retainSourceTintAlpha(sourceIdentity)
                            | resolve(
                                    PrimitivePacking.unpackSourceMediumId(sourceIdentity),
                                    localToRenderer);
        }
        return result;
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
