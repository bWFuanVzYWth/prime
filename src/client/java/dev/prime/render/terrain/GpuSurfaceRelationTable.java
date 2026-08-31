package dev.prime.render.terrain;

import java.util.Arrays;

/** Upload-only relation encoding addressed from existing primitive and emitter payload words. */
public final class GpuSurfaceRelationTable {
    public static final int BOUNDARY_WORDS = 3;
    public static final int MATERIAL_WORDS = 7;
    private static final int MAX_ENCODED_OFFSET = 0x00ff_ffff;
    private static final int STATIC_PAYLOAD_MASK = PrimitivePacking.MAX_TEXTURE_ID << 3;
    private static final int MATERIAL_TANGENT_NEGATIVE = 0x8000_0000;

    private GpuSurfaceRelationTable() {
    }

    public static long byteSize(CpuClusterMesh mesh) {
        if (!mesh.hasSurfaceRelations()) {
            return 0L;
        }
        long words = 0L;
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

    public static Encoding encodeResolved(
            int[] table, int primitiveCount, int emitterCount) {
        if (primitiveCount < 0 || emitterCount < 0) {
            throw new IllegalArgumentException("GPU relation counts must be non-negative");
        }
        if (table.length == 0) {
            return new Encoding(
                    new int[0], new int[primitiveCount], new int[emitterCount]);
        }
        SurfaceRelationTable.validate(table, primitiveCount);
        int wordCount = Math.toIntExact(compactTailWords(table, primitiveCount));
        int[] words = new int[wordCount];
        int[] primitiveOffsets = new int[primitiveCount];
        int cursor = 0;
        for (int primitive = 0; primitive < primitiveCount; primitive++) {
            int[] record = SurfaceRelationTable.record(table, primitiveCount, primitive);
            if (record == null) {
                continue;
            }
            int encodedOffset = Math.addExact(cursor, 1);
            if (encodedOffset > MAX_ENCODED_OFFSET) {
                throw new IllegalArgumentException(
                        "GPU relation tail exceeds its exact 24-bit word offset");
            }
            primitiveOffsets[primitive] = encodedOffset;
            int kind = record[0] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
            if (kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY) {
                int identity = record[4];
                if (MaterialIdResolver.unpackMaterialId(identity) == 0) {
                    throw new IllegalArgumentException(
                            "GPU boundary relation requires a resolved MaterialId");
                }
                if ((record[2] & ~TintIdResolver.MAX_TINT_ID) != 0) {
                    throw new IllegalArgumentException(
                            "GPU boundary relation requires a resolved u16 TintId");
                }
                words[cursor] = record[0] & 0xff;
                words[cursor + 1] = record[1];
                words[cursor + 2] = MaterialIdResolver.pack(
                        record[2] & TintIdResolver.MAX_TINT_ID,
                        MaterialIdResolver.unpackMaterialId(identity));
                cursor += BOUNDARY_WORDS;
            } else {
                int materialId = MaterialIdResolver.unpackMaterialId(
                        record[1 + PrimitivePacking.MEDIUM_ID_WORD]);
                if (materialId == 0) {
                    throw new IllegalArgumentException(
                            "GPU material relation requires a resolved MaterialId");
                }
                if ((record[4] & 0x00ff_0000) != 0) {
                    throw new IllegalArgumentException(
                            "GPU material relation requires a resolved u16 TintId");
                }
                words[cursor] = record[0]
                        | ((record[4] & PrimitivePacking.CONTROL_TANGENT_NEGATIVE << 24) != 0
                                ? MATERIAL_TANGENT_NEGATIVE
                                : 0);
                words[cursor + 1] = record[1];
                words[cursor + 2] = record[2];
                words[cursor + 3] = record[3];
                words[cursor + 4] = MaterialIdResolver.pack(
                        record[4] & TintIdResolver.MAX_TINT_ID, materialId);
                words[cursor + 5] = record[7];
                words[cursor + 6] = record[8];
                cursor += MATERIAL_WORDS;
            }
        }
        if (cursor != words.length) {
            throw new IllegalStateException("GPU relation tail size changed while encoding");
        }
        return new Encoding(words, primitiveOffsets, new int[emitterCount]);
    }

    public static int[] primitiveRecords(
            int[] source,
            int opaqueCount,
            int cutoutCount,
            int transmissiveCount,
            int opaqueRelationBase,
            int cutoutRelationBase,
            int transmissiveRelationBase,
            Encoding relations) {
        int primitiveCount = Math.addExact(
                Math.addExact(opaqueCount, cutoutCount), transmissiveCount);
        int expectedWords = Math.multiplyExact(
                primitiveCount, CpuSectionMesh.PRIMITIVE_WORDS);
        if (opaqueCount < 0
                || cutoutCount < 0
                || transmissiveCount < 0
                || source.length != expectedWords) {
            throw new IllegalArgumentException(
                    "GPU relation packing requires complete primitive categories");
        }
        int[] result = source.clone();
        int[] counts = {opaqueCount, cutoutCount, transmissiveCount};
        int[] relationBases = {
            opaqueRelationBase, cutoutRelationBase, transmissiveRelationBase
        };
        int localPrimitive = 0;
        for (int category = 0; category < counts.length; category++) {
            for (int index = 0; index < counts[category]; index++) {
                int relationPrimitive = Math.addExact(relationBases[category], index);
                int encodedOffset = relations.primitiveOffset(relationPrimitive);
                int base = localPrimitive * CpuSectionMesh.PRIMITIVE_WORDS;
                int materialId = MaterialIdResolver.unpackMaterialId(
                        result[base + PrimitivePacking.MEDIUM_ID_WORD]);
                int flags = result[base + 5];
                int emitter = PrimitivePacking.unpackEmitterIndex(flags);
                if (materialId == 0) {
                    if (emitter != PrimitivePacking.NO_EMITTER_INDEX) {
                        throw new IllegalArgumentException(
                                "A compiled emitter must have a table-backed material");
                    }
                    if (encodedOffset != 0) {
                        throw new IllegalArgumentException(
                                "A non-table primitive cannot own a GPU surface relation");
                    }
                } else if (emitter != PrimitivePacking.NO_EMITTER_INDEX) {
                    relations.bindEmitter(emitter, encodedOffset);
                } else {
                    result[base + 5] = flags & ~STATIC_PAYLOAD_MASK
                            | encodedOffset << 3;
                }
                localPrimitive++;
            }
        }
        return result;
    }

    static int[] record(Encoding encoding, int primitive) {
        int encodedOffset = encoding.primitiveOffset(primitive);
        if (encodedOffset == 0) {
            return null;
        }
        int offset = encodedOffset - 1;
        int words = wordsForControl(encoding.words[offset]);
        return Arrays.copyOfRange(encoding.words, offset, offset + words);
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
                : MATERIAL_WORDS;
    }

    /** Invocation-local mutable upload product; one scene update owns and completes it. */
    public static final class Encoding {
        private final int[] words;
        private final int[] primitiveOffsets;
        private final int[] emitterOffsets;
        private final boolean[] emitterSeen;

        private Encoding(
                int[] words, int[] primitiveOffsets, int[] emitterOffsets) {
            this.words = words;
            this.primitiveOffsets = primitiveOffsets;
            this.emitterOffsets = emitterOffsets;
            this.emitterSeen = new boolean[emitterOffsets.length];
        }

        public int[] words() {
            return this.words;
        }

        public long byteSize() {
            return (long) this.words.length * Integer.BYTES;
        }

        public boolean isEmpty() {
            return this.words.length == 0;
        }

        public int[] completedEmitterOffsets() {
            for (boolean seen : this.emitterSeen) {
                if (!seen) {
                    throw new IllegalStateException(
                            "A compiled emitter has no primitive owner");
                }
            }
            return this.emitterOffsets;
        }

        private int primitiveOffset(int primitive) {
            if (primitive < 0 || primitive >= this.primitiveOffsets.length) {
                throw new IndexOutOfBoundsException(primitive);
            }
            return this.primitiveOffsets[primitive];
        }

        private void bindEmitter(int emitter, int encodedOffset) {
            if (emitter < 0 || emitter >= this.emitterOffsets.length) {
                throw new IllegalArgumentException(
                        "Primitive references an emitter outside the compiled light table");
            }
            if (this.emitterSeen[emitter]) {
                throw new IllegalArgumentException(
                        "Compiled light emitter is owned by more than one primitive");
            }
            this.emitterSeen[emitter] = true;
            this.emitterOffsets[emitter] = encodedOffset;
        }
    }
}
