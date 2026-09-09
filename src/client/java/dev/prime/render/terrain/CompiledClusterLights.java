// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import dev.prime.render.shader.ShaderAbi;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * Relocatable final light payload produced by cluster compilation.
 *
 * <p>The first four ABI fields are byte offsets when stored here. Upload adds the destination
 * device address without rebuilding emitters, distributions, or light-tree records.
 */
public final class CompiledClusterLights {
    private static final int POINTER_COUNT = 4;
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

    EmitterMaterial emitterMaterial(int emitterIndex) {
        if (emitterIndex < 0 || emitterIndex >= this.emitterCount()) {
            throw new IndexOutOfBoundsException(emitterIndex);
        }
        int emitterWords = ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES;
        int emitterStart = Math.toIntExact(getLong(this.relativeWords, 4) / Integer.BYTES);
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
        int emitterStart = Math.toIntExact(getLong(relocated, 4) / Integer.BYTES);
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
