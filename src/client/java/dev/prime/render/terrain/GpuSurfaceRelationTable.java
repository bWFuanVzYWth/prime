package dev.prime.render.terrain;

import java.util.ArrayList;
import java.util.List;

/** Upload-only relation encoding that resolves boundary material facts through MaterialId. */
public final class GpuSurfaceRelationTable {
    public static final int BOUNDARY_WORDS = 4;

    private GpuSurfaceRelationTable() {
    }

    public static long byteSize(CpuClusterMesh mesh) {
        if (!mesh.hasSurfaceRelations()) {
            return 0L;
        }
        long words = mesh.primitiveCount();
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            int[] table = segment.surfaceRelationRecords();
            if (table.length == 0) {
                continue;
            }
            int primitiveCount = segment.opaquePrimitiveCount()
                    + segment.cutoutPrimitiveCount()
                    + segment.transmissivePrimitiveCount();
            SurfaceRelationTable.validate(table, primitiveCount);
            words = Math.addExact(words, compactTailWords(table, primitiveCount));
        }
        return Math.multiplyExact(words, Integer.BYTES);
    }

    public static int[] encodeResolved(int[] table, int primitiveCount) {
        if (table.length == 0) {
            return table;
        }
        SurfaceRelationTable.validate(table, primitiveCount);
        ArrayList<int[]> records = new ArrayList<>(primitiveCount);
        for (int primitive = 0; primitive < primitiveCount; primitive++) {
            int[] record = SurfaceRelationTable.record(table, primitiveCount, primitive);
            if (record == null) {
                records.add(null);
                continue;
            }
            int kind = record[0] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
            if (kind != CpuSectionMesh.SURFACE_RELATION_BOUNDARY) {
                records.add(record);
                continue;
            }
            int identity = record[4];
            if (MaterialIdResolver.unpackMaterialId(identity) == 0) {
                throw new IllegalArgumentException(
                        "GPU boundary relation requires a resolved MaterialId");
            }
            records.add(new int[] {
                record[0] & 0xff,
                record[1],
                record[2],
                identity
            });
        }
        return encode(records);
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
        return java.util.Arrays.copyOfRange(table, offset, offset + words);
    }

    private static long compactTailWords(int[] table, int primitiveCount) {
        long words = 0L;
        int cursor = primitiveCount;
        while (cursor < table.length) {
            int sourceWords = SurfaceRelationTable.wordsForControl(table[cursor]);
            words = Math.addExact(words, wordsForControl(table[cursor]));
            cursor += sourceWords;
        }
        return words;
    }

    private static int wordsForControl(int control) {
        return (control & CpuSectionMesh.SURFACE_RELATION_KIND_MASK)
                        == CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                ? BOUNDARY_WORDS
                : 9;
    }

    private static int[] encode(List<int[]> records) {
        int primitiveCount = records.size();
        int wordCount = primitiveCount;
        boolean any = false;
        for (int[] record : records) {
            if (record != null) {
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
}
