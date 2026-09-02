package dev.prime.render.terrain;

import dev.prime.render.shader.ShaderAbi;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * Relocatable final light payload produced by cluster compilation.
 *
 * <p>The first five ABI fields are byte offsets when stored here. Upload adds the destination
 * device address without rebuilding emitters, distributions, or light-tree records.
 */
public final class CompiledClusterLights {
    private static final int POINTER_COUNT = 5;
    private static final int HEADER_WORDS = 12;
    private static final int MAX_RELATION_OFFSET = 0x00ff_ffff;
    public static final CompiledClusterLights EMPTY =
            new CompiledClusterLights(new int[0], Summary.EMPTY);

    private final int[] relativeWords;
    private final Summary summary;

    private CompiledClusterLights(int[] relativeWords, Summary summary) {
        this.relativeWords = relativeWords;
        this.summary = summary;
    }

    static CompiledClusterLights compile(CpuSectionLights source) {
        Objects.requireNonNull(source, "source");
        if (source.isEmpty()) {
            return EMPTY;
        }
        CpuSectionLights.Summary sourceSummary = source.summary();
        return new CompiledClusterLights(
                source.pack(0L),
                new Summary(
                        sourceSummary.emitterCount(),
                        sourceSummary.bounds().minX(),
                        sourceSummary.bounds().minY(),
                        sourceSummary.bounds().minZ(),
                        sourceSummary.bounds().maxX(),
                        sourceSummary.bounds().maxY(),
                        sourceSummary.bounds().maxZ(),
                        sourceSummary.power(),
                        sourceSummary.packedDirection()));
    }

    static CompiledClusterLights fromEncoded(int[] relativeWords, Summary summary) {
        Objects.requireNonNull(relativeWords, "relativeWords");
        Objects.requireNonNull(summary, "summary");
        if (summary.isEmpty()) {
            if (relativeWords.length != 0) {
                throw new IllegalArgumentException(
                        "Empty compiled lights must not contain an encoded payload");
            }
            return EMPTY;
        }
        if (relativeWords.length < HEADER_WORDS) {
            throw new IllegalArgumentException("Compiled light payload is smaller than its header");
        }
        int byteSize = Math.multiplyExact(relativeWords.length, Integer.BYTES);
        long[] offsets = new long[POINTER_COUNT];
        for (int pointer = 0; pointer < POINTER_COUNT; pointer++) {
            long offset = getLong(relativeWords, pointer * 2);
            if (offset < 0L || offset > byteSize || (offset & 3L) != 0L) {
                throw new IllegalArgumentException(
                        "Compiled light payload contains an invalid relative pointer");
            }
            offsets[pointer] = offset;
        }
        if (relativeWords[11] != summary.emitterCount()) {
            throw new IllegalArgumentException(
                    "Compiled light header disagrees with its emitter summary");
        }
        validateLayout(
                relativeWords,
                offsets,
                byteSize,
                summary.emitterCount(),
                summary.packedDirection());
        return new CompiledClusterLights(relativeWords.clone(), summary);
    }

    /** Upgrades the pre-v6 one-word forward stream with conservative full-direction metadata. */
    public boolean isEmpty() {
        return this.summary.isEmpty();
    }

    public int emitterCount() {
        return this.summary.emitterCount();
    }

    public long byteSize() {
        return (long) this.relativeWords.length * Integer.BYTES;
    }

    public Summary summary() {
        return this.summary;
    }

    /** Returns the canonical zero-base ABI words for hashing or replay serialization. */
    public int[] encodedWords() {
        return this.relativeWords.clone();
    }

    EmitterMaterial emitterMaterial(int emitterIndex) {
        if (emitterIndex < 0 || emitterIndex >= this.emitterCount()) {
            throw new IndexOutOfBoundsException(emitterIndex);
        }
        int emitterWords = ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES;
        int emitterStart = Math.toIntExact(getLong(this.relativeWords, 6) / Integer.BYTES);
        int base = emitterStart + emitterIndex * emitterWords;
        int tintWord = ShaderAbi.LIGHT_EMITTER_UVS_TINT_OFFSET / Integer.BYTES + 3;
        int textureWord = ShaderAbi.LIGHT_EMITTER_METADATA_OFFSET / Integer.BYTES + 3;
        return new EmitterMaterial(
                this.relativeWords[base + tintWord] & 0x00ff_ffff,
                this.relativeWords[base + textureWord]);
    }

    /** Returns one owned upload payload relocated to {@code deviceAddress}. */
    public int[] relocate(long deviceAddress) {
        return this.relocate(deviceAddress, null);
    }

    /** Returns one relocated payload whose static RGBA8 tints have exact renderer TintIds. */
    public int[] relocate(long deviceAddress, IntUnaryOperator tintResolver) {
        return this.relocate(deviceAddress, tintResolver, new int[this.emitterCount()]);
    }

    /** Returns one relocated payload with exact tint and per-emitter surface-relation offsets. */
    public int[] relocate(
            long deviceAddress,
            IntUnaryOperator tintResolver,
            int[] relationOffsets) {
        Objects.requireNonNull(relationOffsets, "relationOffsets");
        if (relationOffsets.length != this.emitterCount()) {
            throw new IllegalArgumentException(
                    "Emitter relation offsets disagree with the compiled light table");
        }
        if (this.isEmpty()) {
            return new int[0];
        }
        int[] relocated = this.relativeWords.clone();
        int emitterWords = ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES;
        int emitterStart = Math.toIntExact(getLong(relocated, 6) / Integer.BYTES);
        if (tintResolver != null) {
            int tintWord = ShaderAbi.LIGHT_EMITTER_UVS_TINT_OFFSET / Integer.BYTES + 3;
            for (int emitter = 0; emitter < this.emitterCount(); emitter++) {
                int word = emitterStart + emitter * emitterWords + tintWord;
                relocated[word] = TintIdResolver.resolvePackedRgba(
                        relocated[word], tintResolver);
            }
        }
        int relationWord = ShaderAbi.LIGHT_EMITTER_RELATION_OFFSET_OFFSET / Integer.BYTES;
        for (int emitter = 0; emitter < relationOffsets.length; emitter++) {
            int relationOffset = relationOffsets[emitter];
            if (relationOffset < 0 || relationOffset > MAX_RELATION_OFFSET) {
                throw new IllegalArgumentException(
                        "Emitter relation offset exceeds its exact 24-bit domain");
            }
            relocated[emitterStart + emitter * emitterWords + relationWord] =
                    relationOffset;
        }
        if (deviceAddress == 0L) {
            return relocated;
        }
        for (int pointer = 0; pointer < POINTER_COUNT; pointer++) {
            int word = pointer * 2;
            putLong(
                    relocated,
                    word,
                    Math.addExact(deviceAddress, getLong(relocated, word)));
        }
        return relocated;
    }

    private static long getLong(int[] words, int offset) {
        return Integer.toUnsignedLong(words[offset])
                | (long) words[offset + 1] << 32;
    }

    private static void putLong(int[] words, int offset, long value) {
        words[offset] = (int) value;
        words[offset + 1] = (int) (value >>> 32);
    }

    record EmitterMaterial(int packedTint, int textureId) {
        EmitterMaterial {
            if ((packedTint & 0xff00_0000) != 0
                    || textureId <= 0
                    || textureId > PrimitivePacking.MAX_TEXTURE_ID) {
                throw new IllegalStateException(
                        "Compiled light emitter has an invalid material identity");
            }
        }
    }

    private static void validateLayout(
            int[] words,
            long[] offsets,
            int byteSize,
            int emitterCount,
            int packedDirection) {
        long nodeStart = offsets[0];
        long leafStart = offsets[1];
        long leafEnd = offsets[2];
        long emitterStart = offsets[3];
        long cellStart = offsets[4];
        long headerBytes = (long) HEADER_WORDS * Integer.BYTES;
        if (words[10] != 0
                || nodeStart != headerBytes
                || leafStart < nodeStart
                || leafEnd < leafStart
                || emitterStart < leafEnd
                || cellStart < emitterStart) {
            throw new IllegalArgumentException(
                    "Compiled light payload has an invalid section order");
        }
        long nodeBytes = leafStart - nodeStart;
        if (nodeBytes % ShaderAbi.LIGHT_NODE_SIZE != 0L) {
            throw new IllegalArgumentException(
                    "Compiled light node stream is misaligned");
        }
        long nodeCount = nodeBytes / ShaderAbi.LIGHT_NODE_SIZE;
        long leafBytes = leafEnd - leafStart;
        if (leafBytes % ShaderAbi.LIGHT_LEAF_SIZE != 0L) {
            throw new IllegalArgumentException("Compiled light leaf streams are misaligned");
        }
        long leafCount = leafBytes / ShaderAbi.LIGHT_LEAF_SIZE;
        long expectedEmitter = alignUp(leafEnd, 16L);
        long expectedCells = Math.addExact(
                emitterStart,
                Math.multiplyExact(
                        (long) emitterCount, ShaderAbi.LIGHT_EMITTER_SIZE));
        long distributionBytes = Math.multiplyExact(
                (long) EmissionDistribution.CELL_COUNT,
                ShaderAbi.LIGHT_CELL_SIZE);
        long distributionCount = (byteSize - cellStart) / distributionBytes;
        long expectedNodeCount = Math.subtractExact(
                Math.multiplyExact((long) emitterCount, 2L), 1L);
        if (emitterStart != expectedEmitter
                || cellStart != expectedCells
                || (byteSize - cellStart) % distributionBytes != 0L
                || nodeCount != expectedNodeCount
                || leafCount != emitterCount
                || distributionCount == 0L) {
            throw new IllegalArgumentException(
                    "Compiled light payload disagrees with the shader ABI");
        }
        int rootDirectionWord = Math.toIntExact(
                (nodeStart
                                + ShaderAbi.LIGHT_NODE_DIRECTION_CHILD_RESERVED_OFFSET)
                        / Integer.BYTES);
        if (words[rootDirectionWord] != packedDirection) {
            throw new IllegalArgumentException(
                    "Compiled light summary disagrees with its root direction");
        }
        validateTreeAndEmitterReferences(
                words,
                nodeStart,
                leafStart,
                emitterStart,
                nodeCount,
                leafCount,
                emitterCount,
                distributionCount);
    }

    private static void validateTreeAndEmitterReferences(
            int[] words,
            long nodeStart,
            long leafStart,
            long emitterStart,
            long nodeCount,
            long leafCount,
            int emitterCount,
            long distributionCount) {
        int nodeWord = Math.toIntExact(nodeStart / Integer.BYTES);
        int leafWord = Math.toIntExact(leafStart / Integer.BYTES);
        int nodeWords = ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES;
        int leafWords = ShaderAbi.LIGHT_LEAF_SIZE / Integer.BYTES;
        int centroidPowerWord = ShaderAbi.LIGHT_NODE_CENTROID_POWER_OFFSET / Integer.BYTES;
        int controlWord =
                ShaderAbi.LIGHT_NODE_DIRECTION_CHILD_RESERVED_OFFSET / Integer.BYTES;
        int childOrLeafWord = controlWord + 1;
        for (int node = 0; node < nodeCount; node++) {
            int base = nodeWord + node * nodeWords;
            float centroidX = Float.intBitsToFloat(words[base + centroidPowerWord]);
            float centroidY = Float.intBitsToFloat(words[base + centroidPowerWord + 1]);
            float centroidZ = Float.intBitsToFloat(words[base + centroidPowerWord + 2]);
            float power = Float.intBitsToFloat(words[base + centroidPowerWord + 3]);
            if (!Float.isFinite(centroidX)
                    || !Float.isFinite(centroidY)
                    || !Float.isFinite(centroidZ)
                    || !(power > 0.0F)
                    || !Float.isFinite(power)
                    || words[base + controlWord + 2] != 0
                    || words[base + controlWord + 3] != 0) {
                throw new IllegalArgumentException("Compiled light tree node is invalid");
            }
            int childOrLeaf = words[base + childOrLeafWord];
            if ((childOrLeaf & CpuLightTree.LEAF_FLAG) != 0) {
                if ((childOrLeaf & CpuLightTree.INDEX_MASK) >= leafCount) {
                    throw new IllegalArgumentException(
                            "Compiled light tree contains an invalid leaf");
                }
            } else if (childOrLeaf < 0
                    || childOrLeaf + 1L >= nodeCount) {
                throw new IllegalArgumentException(
                        "Compiled light tree contains invalid children");
            }
        }

        boolean[] seenEmitters = new boolean[emitterCount];
        for (int leaf = 0; leaf < leafCount; leaf++) {
            int base = leafWord + leaf * leafWords;
            int emitter = words[base];
            float power = Float.intBitsToFloat(words[base + 1]);
            if (emitter < 0
                    || emitter >= emitterCount
                    || seenEmitters[emitter]
                    || !(power > 0.0F)
                    || !Float.isFinite(power)) {
                throw new IllegalArgumentException("Compiled light leaf is invalid");
            }
            seenEmitters[emitter] = true;
        }

        int emitterWords = ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES;
        int metadataWord =
                ShaderAbi.LIGHT_EMITTER_METADATA_OFFSET / Integer.BYTES;
        int emitterWord = Math.toIntExact(emitterStart / Integer.BYTES);
        for (int emitter = 0; emitter < emitterCount; emitter++) {
            int metadata = emitterWord + emitter * emitterWords + metadataWord;
            long firstCell = Integer.toUnsignedLong(words[metadata]);
            int path = words[metadata + 1];
            if (firstCell % EmissionDistribution.CELL_COUNT != 0L
                    || firstCell / EmissionDistribution.CELL_COUNT
                            >= distributionCount
                    || !pathContainsEmitter(
                            words,
                            nodeWord,
                            nodeWords,
                            childOrLeafWord,
                            leafWord,
                            leafWords,
                            path,
                            emitter)) {
                throw new IllegalArgumentException(
                        "Compiled light emitter references invalid tree or distribution data");
            }
        }
    }

    private static boolean pathContainsEmitter(
            int[] words,
            int nodeWord,
            int nodeWords,
            int childOrLeafWord,
            int leafWord,
            int leafWords,
            int path,
            int expectedEmitter) {
        int depth = path >>> CpuLightTree.PATH_DEPTH_SHIFT;
        int trail = path & CpuLightTree.PATH_TRAIL_MASK;
        if (depth > CpuLightTree.MAX_PATH_DEPTH
                || (depth < CpuLightTree.MAX_PATH_DEPTH && (trail >>> depth) != 0)) {
            return false;
        }
        int node = 0;
        for (int level = 0; level < depth; level++) {
            int child = words[nodeWord + node * nodeWords + childOrLeafWord];
            if ((child & CpuLightTree.LEAF_FLAG) != 0) {
                return false;
            }
            int selected = (trail >>> level) & 1;
            node = child + selected;
        }
        int childOrLeaf = words[nodeWord + node * nodeWords + childOrLeafWord];
        if ((childOrLeaf & CpuLightTree.LEAF_FLAG) == 0) {
            return false;
        }
        int leaf = childOrLeaf & CpuLightTree.INDEX_MASK;
        return words[leafWord + leaf * leafWords] == expectedEmitter;
    }

    private static long alignUp(long value, long alignment) {
        return Math.addExact(value, alignment - 1L) / alignment * alignment;
    }

    public record Summary(
            int emitterCount,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float power,
            int packedDirection) {
        private static final Summary EMPTY =
                new Summary(
                        0,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        LightDirection.FULL);

        public Summary(
                int emitterCount,
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                float power) {
            this(
                    emitterCount,
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    power,
                    LightDirection.FULL);
        }

        public Summary {
            if (emitterCount < 0) {
                throw new IllegalArgumentException("Emitter count must not be negative");
            }
            if (!Float.isFinite(minX)
                    || !Float.isFinite(minY)
                    || !Float.isFinite(minZ)
                    || !Float.isFinite(maxX)
                    || !Float.isFinite(maxY)
                    || !Float.isFinite(maxZ)
                    || !Float.isFinite(power)) {
                throw new IllegalArgumentException("Compiled light summary must be finite");
            }
            if (emitterCount == 0) {
                if (power != 0.0F || packedDirection != LightDirection.FULL) {
                    throw new IllegalArgumentException(
                            "Empty compiled lights must have zero power and full directional support");
                }
            } else if (!(power > 0.0F)
                    || minX > maxX
                    || minY > maxY
                    || minZ > maxZ) {
                throw new IllegalArgumentException("Compiled light summary is inconsistent");
            }
        }

        public boolean isEmpty() {
            return this.emitterCount == 0;
        }

        CpuLightTree.Bounds bounds() {
            if (this.isEmpty()) {
                throw new IllegalStateException("Empty compiled lights have no bounds");
            }
            return new CpuLightTree.Bounds(
                    this.minX,
                    this.minY,
                    this.minZ,
                    this.maxX,
                    this.maxY,
                    this.maxZ);
        }
    }
}
