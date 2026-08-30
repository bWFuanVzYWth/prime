package dev.prime.render.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Sparse per-primitive relation table: a dense reference header followed by typed records. */
final class SurfaceRelationTable {
    private SurfaceRelationTable() {
    }

    static int[] encode(List<int[]> records) {
        int primitiveCount = records.size();
        int wordCount = primitiveCount;
        boolean any = false;
        for (int[] record : records) {
            if (record != null) {
                if (record.length == 0) {
                    throw new IllegalArgumentException("Surface relation record is empty");
                }
                any = true;
                wordCount = Math.addExact(wordCount, record.length);
            }
        }
        if (!any) {
            return new int[0];
        }
        int[] result = new int[wordCount];
        int cursor = primitiveCount;
        for (int primitive = 0; primitive < primitiveCount; primitive++) {
            int[] record = records.get(primitive);
            if (record == null) {
                continue;
            }
            result[primitive] = cursor;
            System.arraycopy(record, 0, result, cursor, record.length);
            cursor += record.length;
        }
        return result;
    }

    static int[] record(int[] table, int primitiveCount, int primitive) {
        if (table.length == 0) {
            return null;
        }
        if (primitive < 0 || primitive >= primitiveCount) {
            throw new IndexOutOfBoundsException(primitive);
        }
        int offset = table[primitive];
        if (offset == 0) {
            return null;
        }
        int words = wordsForControl(table[offset]);
        return Arrays.copyOfRange(table, offset, offset + words);
    }

    static void appendRange(
            ArrayList<int[]> destination,
            int[] table,
            int primitiveCount,
            int firstPrimitive,
            int count) {
        for (int index = 0; index < count; index++) {
            destination.add(record(
                    table, primitiveCount, firstPrimitive + index));
        }
    }

    static void validate(int[] table, int primitiveCount) {
        if (table.length == 0) {
            return;
        }
        if (table.length < primitiveCount) {
            throw new IllegalArgumentException(
                    "Surface-relation table is shorter than its primitive header");
        }
        boolean[] recordStart = new boolean[table.length];
        for (int primitive = 0; primitive < primitiveCount; primitive++) {
            int offset = table[primitive];
            if (offset == 0) {
                continue;
            }
            if (offset < primitiveCount || offset >= table.length) {
                throw new IllegalArgumentException(
                        "Surface-relation reference is outside the sparse record tail");
            }
            int words = wordsForControl(table[offset]);
            if (offset + words > table.length) {
                throw new IllegalArgumentException(
                        "Surface-relation record exceeds its table");
            }
            int kind = table[offset] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
            if (kind == CpuSectionMesh.SURFACE_RELATION_OVERLAY
                    || kind == CpuSectionMesh.SURFACE_RELATION_BILATERAL) {
                validateMaterial(table, offset + 1);
            } else if (kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                    && (table[offset + 3] <= 0
                            || table[offset + 3] > PrimitivePacking.MAX_TEXTURE_ID)) {
                throw new IllegalArgumentException(
                        "Boundary relation references an invalid texture ID");
            }
            recordStart[offset] = true;
        }
        int cursor = primitiveCount;
        while (cursor < table.length) {
            if (!recordStart[cursor]) {
                throw new IllegalArgumentException(
                        "Surface-relation table contains unreachable record words");
            }
            cursor += wordsForControl(table[cursor]);
        }
    }

    private static void validateMaterial(int[] table, int offset) {
        int flags = PrimitivePacking.unpackControl(
                table[offset + 3], table[offset + 5]);
        PrimitivePacking.requireValidControl(flags);
        if ((table[offset + 5] & PrimitivePacking.DYNAMIC_TEXTURE_FLAG) != 0
                || PrimitivePacking.unpackEmitterIndex(table[offset + 5])
                        != PrimitivePacking.NO_EMITTER_INDEX
                || PrimitivePacking.unpackTextureId(table[offset + 5]) == 0
                || (flags & PrimitivePacking.CONTROL_FRONT_FACE_ONLY) != 0) {
            throw new IllegalArgumentException(
                    "Surface relation embeds an unsupported material primitive");
        }
    }

    static int wordsForControl(int control) {
        int kind = control & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
        return switch (kind) {
            case CpuSectionMesh.SURFACE_RELATION_BOUNDARY -> {
                int allowed = CpuSectionMesh.SURFACE_RELATION_KIND_MASK
                        | CpuSectionMesh.SURFACE_RELATION_MICRO_GAP_ELIGIBLE;
                int recipe = control >>> 8;
                if ((control & 0xff & ~allowed) != 0
                        || (recipe & ~PrimitivePacking.MATERIAL_RECIPE_MASK) != 0) {
                    throw new IllegalArgumentException(
                            "Boundary relation contains invalid control flags");
                }
                PrimitivePacking.requireValidControl(recipe);
                yield 4;
            }
            case CpuSectionMesh.SURFACE_RELATION_OVERLAY -> {
                int lowAllowed = CpuSectionMesh.SURFACE_RELATION_KIND_MASK
                        | CpuSectionMesh.SURFACE_RELATION_POSITIVE_ONLY;
                int primaryFlags = control >>> 8;
                if ((control & 0xff & ~lowAllowed) != 0
                        || (primaryFlags & ~PrimitivePacking.MATERIAL_RECIPE_MASK) != 0
                        || !PrimitivePacking.isCutout(primaryFlags)) {
                    throw new IllegalArgumentException(
                            "Overlay relation contains invalid material flags");
                }
                PrimitivePacking.requireValidControl(primaryFlags);
                yield 9;
            }
            case CpuSectionMesh.SURFACE_RELATION_BILATERAL -> {
                if (control != kind) {
                    throw new IllegalArgumentException(
                            "Bilateral relation contains invalid control flags");
                }
                yield 9;
            }
            default -> throw new IllegalArgumentException(
                    "Unknown surface relation kind: "
                            + kind);
        };
    }
}
