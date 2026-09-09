// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

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
        long words = 0L;
        for (CpuMeshSegment segment : mesh.segments()) {
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

    public static Encoding encode(
            CpuClusterMesh mesh,
            int emitterCount,
            MaterialIdResolver.Cache materials,
            IntUnaryOperator tintResolver) {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(tintResolver, "tintResolver");
        if (emitterCount < 0) {
            throw new IllegalArgumentException("GPU emitter count must be non-negative");
        }
        int primitiveCount = Math.toIntExact(mesh.triangleLayout().primitiveCount());
        Encoding encoding = new Encoding(
                new int[Math.toIntExact(byteSize(mesh) / Integer.BYTES)],
                new int[primitiveCount],
                new int[emitterCount]);
        int cursor = 0;
        int primitive = 0;
        for (int category = 0; category < 3; category++) {
            for (CpuMeshSegment segment : mesh.segments()) {
                int count = primitiveCount(segment, category);
                int first = firstPrimitive(segment, category);
                int[] table = segment.surfaceRelationRecords();
                for (int index = 0; index < count; index++, primitive++) {
                    int record = table.length == 0 ? 0 : table[first + index];
                    if (record == 0) {
                        continue;
                    }
                    int encodedOffset = Math.addExact(cursor, 1);
                    if (encodedOffset > MAX_ENCODED_OFFSET) {
                        throw new IllegalArgumentException(
                                "GPU relation tail exceeds its exact 24-bit word offset");
                    }
                    encoding.primitiveOffsets[primitive] = encodedOffset;
                    cursor = encodeRecord(
                            table,
                            record,
                            encoding.words,
                            cursor,
                            materials,
                            tintResolver);
                }
            }
        }
        if (primitive != primitiveCount || cursor != encoding.words.length) {
            throw new IllegalStateException("GPU relation tail size changed while encoding");
        }
        return encoding;
    }

    private static int encodeRecord(
            int[] source,
            int record,
            int[] target,
            int cursor,
            MaterialIdResolver.Cache materials,
            IntUnaryOperator tintResolver) {
        int control = source[record];
        int kind = control & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
        if (kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY) {
            int materialId = materials.boundaryId(source, record);
            int tintId = TintIdResolver.resolvePackedRgba(
                    source[record + 2], tintResolver);
            target[cursor] = control & 0xff;
            target[cursor + 1] = source[record + 1];
            target[cursor + 2] = MaterialIdResolver.pack(tintId, materialId);
            return cursor + BOUNDARY_WORDS;
        }
        int material = record + 1;
        int materialId = materials.primitiveId(
                source, material, CompiledClusterLights.EMPTY);
        if (materialId == 0) {
            throw new IllegalArgumentException(
                    "GPU surface relation requires a table-backed material");
        }
        int packedRgba = source[material + 3] & 0x00ff_ffff
                | PrimitivePacking.unpackSourceTintAlpha(
                        source[material + PrimitivePacking.MEDIUM_ID_WORD]);
        int tintId = TintIdResolver.resolvePackedRgba(packedRgba, tintResolver);
        target[cursor] = control
                | ((source[material + 3]
                                & PrimitivePacking.CONTROL_TANGENT_NEGATIVE << 24)
                        != 0 ? MATERIAL_TANGENT_NEGATIVE : 0);
        target[cursor + 1] = source[material];
        target[cursor + 2] = source[material + 1];
        target[cursor + 3] = source[material + 2];
        target[cursor + 4] = MaterialIdResolver.pack(tintId, materialId);
        target[cursor + 5] = source[material + 6];
        target[cursor + 6] = source[material + 7];
        return cursor + MATERIAL_WORDS;
    }

    private static int firstPrimitive(CpuMeshSegment segment, int category) {
        return switch (category) {
            case 0 -> 0;
            case 1 -> segment.opaquePrimitiveCount();
            default -> segment.opaquePrimitiveCount() + segment.cutoutPrimitiveCount();
        };
    }

    private static int primitiveCount(CpuMeshSegment segment, int category) {
        return switch (category) {
            case 0 -> segment.opaquePrimitiveCount();
            case 1 -> segment.cutoutPrimitiveCount();
            default -> segment.transmissivePrimitiveCount();
        };
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
