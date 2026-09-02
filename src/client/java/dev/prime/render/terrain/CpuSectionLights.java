package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.shader.ShaderAbi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable light surfaces, local-tree streams and texture distributions for one geometry unit. */
public final class CpuSectionLights {
    public static final CpuSectionLights EMPTY = new CpuSectionLights();
    static final int EMITTER_FLAG_TWO_SIDED = 1;
    static final int EMITTER_FLAG_LABPBR_EMISSION = 2;
    private static final int EMITTER_FLOATS = 15;
    private static final int EMITTER_INTS = 7;
    private static final int CORNER_X = 0;
    private static final int CORNER_Y = 1;
    private static final int CORNER_Z = 2;
    private static final int AREA = 3;
    private static final int EDGE_ONE_X = 4;
    private static final int EDGE_ONE_Y = 5;
    private static final int EDGE_ONE_Z = 6;
    private static final int EMISSION_SCALE = 7;
    private static final int EDGE_TWO_X = 8;
    private static final int EDGE_TWO_Y = 9;
    private static final int EDGE_TWO_Z = 10;
    private static final int POWER = 11;
    private static final int NORMAL_X = 12;
    private static final int NORMAL_Y = 13;
    private static final int NORMAL_Z = 14;
    private static final int UV_0 = 0;
    private static final int UV_1 = 1;
    private static final int UV_2 = 2;
    private static final int TINT = 3;
    private static final int DISTRIBUTION = 4;
    private static final int FLAGS = 5;
    private static final int TEXTURE_ID = 6;

    private final Emitters emitters;
    private final List<EmissionDistribution> distributions;
    private final CpuLightTree.Result tree;

    private CpuSectionLights() {
        this.emitters = new Emitters(0);
        this.distributions = List.of();
        this.tree = null;
    }

    private CpuSectionLights(
            Emitters emitters,
            List<EmissionDistribution> distributions,
            CpuLightTree.Result tree) {
        // Builder ownership ends at build(); neither list is exposed or mutated afterwards.
        this.emitters = emitters;
        this.distributions = distributions;
        this.tree = tree;
    }

    public boolean isEmpty() {
        return this.emitters.size == 0;
    }

    public int emitterCount() {
        return this.emitters.size;
    }

    public long byteSize() {
        if (this.isEmpty()) {
            return 0L;
        }
        long emitterOffset = alignUp(
                ShaderAbi.SECTION_LIGHT_HEADER_SIZE
                        + (long) this.tree.nodeCount() * ShaderAbi.LIGHT_NODE_SIZE
                        + (long) this.tree.leafCount() * ShaderAbi.LIGHT_LEAF_SIZE,
                16L);
        return emitterOffset + (long) this.emitters.size * ShaderAbi.LIGHT_EMITTER_SIZE
                + (long) this.distributions.size()
                        * EmissionDistribution.CELL_COUNT
                        * ShaderAbi.LIGHT_CELL_SIZE;
    }

    public int[] pack(long bufferAddress) {
        if (this.isEmpty()) {
            return new int[0];
        }
        int headerWords = ShaderAbi.SECTION_LIGHT_HEADER_SIZE / Integer.BYTES;
        int nodeWords = this.tree.nodeCount() * (ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES);
        int leafWords = this.tree.leafCount()
                * (ShaderAbi.LIGHT_LEAF_SIZE / Integer.BYTES);
        int emitterWords = ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES;
        int cellWords = ShaderAbi.LIGHT_CELL_SIZE / Integer.BYTES;
        int cellCount = this.distributions.size() * EmissionDistribution.CELL_COUNT;
        int nodeStart = headerWords;
        int leafStart = nodeStart + nodeWords;
        int leafEnd = leafStart + leafWords;
        int emitterStart = (int) (alignUp(
                        (long) leafEnd * Integer.BYTES,
                        16L)
                / Integer.BYTES);
        int cellStart = emitterStart + this.emitters.size * emitterWords;
        int[] result = new int[cellStart + cellCount * cellWords];
        putLong(result, 0, bufferAddress + (long) nodeStart * Integer.BYTES);
        putLong(result, 2, bufferAddress + (long) leafStart * Integer.BYTES);
        putLong(result, 4, bufferAddress + (long) leafEnd * Integer.BYTES);
        putLong(result, 6, bufferAddress + (long) emitterStart * Integer.BYTES);
        putLong(result, 8, bufferAddress + (long) cellStart * Integer.BYTES);
        result[10] = 0;
        result[11] = this.emitters.size;
        this.tree.packInto(result, nodeStart, leafStart);

        for (int index = 0; index < this.emitters.size; index++) {
            int floatBase = index * EMITTER_FLOATS;
            int intBase = index * EMITTER_INTS;
            int cursor = emitterStart + index * emitterWords;
            putFloat(result, cursor, this.emitters.values[floatBase + CORNER_X]);
            putFloat(result, cursor + 1, this.emitters.values[floatBase + CORNER_Y]);
            putFloat(result, cursor + 2, this.emitters.values[floatBase + CORNER_Z]);
            putFloat(result, cursor + 3, this.emitters.values[floatBase + AREA]);
            putFloat(result, cursor + 4, this.emitters.values[floatBase + EDGE_ONE_X]);
            putFloat(result, cursor + 5, this.emitters.values[floatBase + EDGE_ONE_Y]);
            putFloat(result, cursor + 6, this.emitters.values[floatBase + EDGE_ONE_Z]);
            putFloat(result, cursor + 7, this.emitters.values[floatBase + EMISSION_SCALE]);
            putFloat(result, cursor + 8, this.emitters.values[floatBase + EDGE_TWO_X]);
            putFloat(result, cursor + 9, this.emitters.values[floatBase + EDGE_TWO_Y]);
            putFloat(result, cursor + 10, this.emitters.values[floatBase + EDGE_TWO_Z]);
            putFloat(result, cursor + 11, this.emitters.values[floatBase + POWER]);
            putFloat(result, cursor + 12, this.emitters.values[floatBase + NORMAL_X]);
            putFloat(result, cursor + 13, this.emitters.values[floatBase + NORMAL_Y]);
            putFloat(result, cursor + 14, this.emitters.values[floatBase + NORMAL_Z]);
            result[cursor + 15] = 0;
            result[cursor + 16] = this.emitters.metadata[intBase + UV_0];
            result[cursor + 17] = this.emitters.metadata[intBase + UV_1];
            result[cursor + 18] = this.emitters.metadata[intBase + UV_2];
            result[cursor + 19] = this.emitters.metadata[intBase + TINT];
            result[cursor + 20] = this.emitters.metadata[intBase + DISTRIBUTION]
                    * EmissionDistribution.CELL_COUNT;
            result[cursor + 21] = this.tree.leafPath(index);
            result[cursor + 22] = this.emitters.metadata[intBase + FLAGS];
            result[cursor + 23] = this.emitters.metadata[intBase + TEXTURE_ID];
        }

        for (int distributionIndex = 0; distributionIndex < this.distributions.size(); distributionIndex++) {
            EmissionDistribution distribution = this.distributions.get(distributionIndex);
            for (int cell = 0; cell < EmissionDistribution.CELL_COUNT; cell++) {
                int cursor = cellStart
                        + (distributionIndex * EmissionDistribution.CELL_COUNT + cell) * cellWords;
                putFloat(result, cursor, distribution.aliasProbability(cell));
                result[cursor + 1] = EmissionDistribution.packAliasGeometry(
                        distribution.alias(cell), cell);
                putFloat(result, cursor + 2, distribution.probabilityMass(cell));
            }
        }
        if ((long) result.length * Integer.BYTES != this.byteSize()) {
            throw new IllegalStateException("Packed local light layout does not match the shader ABI");
        }
        return result;
    }

    CpuLightTree.Bounds bounds() {
        if (this.isEmpty()) {
            throw new IllegalStateException("Empty geometry unit has no light bounds");
        }
        return this.tree.bounds();
    }

    float power() {
        return this.isEmpty() ? 0.0F : this.tree.power();
    }

    static CpuSectionLights merge(List<Translated> sources) {
        if (sources.isEmpty()) {
            return EMPTY;
        }
        int emitterCount = 0;
        int distributionCount = 0;
        for (Translated source : sources) {
            emitterCount = Math.addExact(emitterCount, source.lights.emitters.size);
            distributionCount = Math.addExact(
                    distributionCount, source.lights.distributions.size());
        }
        if (emitterCount == 0) {
            return EMPTY;
        }
        Emitters mergedEmitters = new Emitters(emitterCount);
        ArrayList<EmissionDistribution> mergedDistributions = new ArrayList<>(distributionCount);
        HashMap<EmissionDistribution, Integer> distributionIndices = new HashMap<>(distributionCount);
        for (Translated source : sources) {
            int[] remap = new int[source.lights.distributions.size()];
            for (int index = 0; index < remap.length; index++) {
                EmissionDistribution distribution = source.lights.distributions.get(index);
                Integer mergedIndex = distributionIndices.get(distribution);
                if (mergedIndex == null) {
                    mergedIndex = mergedDistributions.size();
                    mergedDistributions.add(distribution);
                    distributionIndices.put(distribution, mergedIndex);
                }
                remap[index] = mergedIndex;
            }
            for (int emitter = 0; emitter < source.lights.emitters.size; emitter++) {
                source.lights.emitters.addTranslatedTo(
                        mergedEmitters, emitter, source.x, source.y, source.z, remap);
            }
        }
        return build(mergedEmitters, mergedDistributions);
    }

    Summary summary() {
        return this.isEmpty()
                ? Summary.EMPTY
                : new Summary(
                        this.emitters.size,
                        this.tree.bounds(),
                        this.tree.power(),
                        this.tree.packedDirection());
    }

    private static void putLong(int[] target, int wordOffset, long value) {
        target[wordOffset] = (int) value;
        target[wordOffset + 1] = (int) (value >>> 32);
    }

    private static void putFloat(int[] target, int wordOffset, float value) {
        target[wordOffset] = Float.floatToRawIntBits(value);
    }

    private static long alignUp(long value, long alignment) {
        return (value + alignment - 1L) / alignment * alignment;
    }

    static final class Builder {
        private final Emitters emitters = new Emitters(16);
        private final Map<EmissionDistribution.Key, Integer> distributionIndices = new HashMap<>();
        private final List<EmissionDistribution> distributions = new ArrayList<>();
        private final Map<EmissionDistribution.Key, EmissionDistribution> buildCache;

        Builder() {
            this(new HashMap<>());
        }

        Builder(Map<EmissionDistribution.Key, EmissionDistribution> buildCache) {
            this.buildCache = Objects.requireNonNull(buildCache, "buildCache");
        }

        int addTriangle(
                float cornerX,
                float cornerY,
                float cornerZ,
                float secondX,
                float secondY,
                float secondZ,
                float thirdX,
                float thirdY,
                float thirdZ,
                int packedUv0,
                int packedUv1,
                int packedUv2,
                int tintArgb,
                boolean cutout,
                int lightEmission,
                CapturedSprite sprite,
                LabPbrEmissionMap labPbrEmission) {
            return this.addTriangle(
                    cornerX,
                    cornerY,
                    cornerZ,
                    secondX,
                    secondY,
                    secondZ,
                    thirdX,
                    thirdY,
                    thirdZ,
                    packedUv0,
                    packedUv1,
                    packedUv2,
                    tintArgb,
                    cutout,
                    cutout,
                    lightEmission,
                    sprite,
                    labPbrEmission);
        }

        int addTriangle(
                float cornerX,
                float cornerY,
                float cornerZ,
                float secondX,
                float secondY,
                float secondZ,
                float thirdX,
                float thirdY,
                float thirdZ,
                int packedUv0,
                int packedUv1,
                int packedUv2,
                int tintArgb,
                boolean cutout,
                boolean twoSided,
                int lightEmission,
                CapturedSprite sprite,
                LabPbrEmissionMap labPbrEmission) {
            if (lightEmission <= 0 && labPbrEmission == null) {
                return 0;
            }
            Objects.requireNonNull(sprite, "Emissive triangle texture");
            float edgeOneX = secondX - cornerX;
            float edgeOneY = secondY - cornerY;
            float edgeOneZ = secondZ - cornerZ;
            float edgeTwoX = thirdX - cornerX;
            float edgeTwoY = thirdY - cornerY;
            float edgeTwoZ = thirdZ - cornerZ;
            float normalX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
            float normalY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
            float normalZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
            double twiceAreaDouble = Math.sqrt(
                    (double) normalX * normalX
                            + (double) normalY * normalY
                            + (double) normalZ * normalZ);
            if (!(twiceAreaDouble > 0.0) || !Double.isFinite(twiceAreaDouble)) {
                return 0;
            }
            float twiceArea = (float) twiceAreaDouble;
            normalX = (float) (normalX / twiceAreaDouble);
            normalY = (float) (normalY / twiceAreaDouble);
            normalZ = (float) (normalZ / twiceAreaDouble);
            float area = 0.5F * twiceArea;
            float scale = emissionScale(lightEmission);
            float vanillaEmissionFraction = scale / ShaderAbi.LEVEL_15_BLOCK_INTENSITY;
            EmissionDistribution.Key key = new EmissionDistribution.Key(
                    sprite,
                    packedUv0,
                    packedUv1,
                    packedUv2,
                    tintArgb,
                    cutout,
                    vanillaEmissionFraction,
                    labPbrEmission);
            Integer cachedDistribution = this.distributionIndices.get(key);
            int distributionIndex;
            if (cachedDistribution != null) {
                distributionIndex = cachedDistribution;
            } else {
                distributionIndex = this.distributions.size();
                this.distributions.add(this.buildCache.computeIfAbsent(
                        key, EmissionDistribution::build));
                this.distributionIndices.put(key, distributionIndex);
            }
            EmissionDistribution distribution = this.distributions.get(distributionIndex);
            if (!distribution.hasSourceSupport()) {
                return 0;
            }
            int flags = (twoSided ? EMITTER_FLAG_TWO_SIDED : 0)
                    | (labPbrEmission != null ? EMITTER_FLAG_LABPBR_EMISSION : 0);
            float sidedness = twoSided ? 2.0F : 1.0F;
            // The distribution already contains either vanilla / level15 or the overriding
            // authored LabPBR alpha.
            // Restore the shared radiometric calibration exactly once when estimating tree power.
            float power = area
                    * (float) Math.PI
                    * sidedness
                    * ShaderAbi.LEVEL_15_BLOCK_INTENSITY
                    * distribution.meanImportance();
            if (!(power > 0.0F) || !Float.isFinite(power)) {
                return 0;
            }
            int index = this.emitters.size;
            this.emitters.add(
                    cornerX,
                    cornerY,
                    cornerZ,
                    edgeOneX,
                    edgeOneY,
                    edgeOneZ,
                    edgeTwoX,
                    edgeTwoY,
                    edgeTwoZ,
                    normalX,
                    normalY,
                    normalZ,
                    area,
                    scale,
                    power,
                    packedUv0,
                    packedUv1,
                    packedUv2,
                    PrimitivePacking.packTint(tintArgb),
                    distributionIndex,
                    flags,
                    sprite.textureId());
            return index + 1;
        }

        CpuSectionLights build() {
            if (this.emitters.size == 0) {
                return EMPTY;
            }
            return CpuSectionLights.build(this.emitters, this.distributions);
        }
    }

    private static CpuSectionLights build(
            Emitters emitters,
            List<EmissionDistribution> distributions) {
        CpuLightTree.Leaves leaves = new CpuLightTree.Leaves(emitters.size);
        for (int index = 0; index < emitters.size; index++) {
            int base = index * EMITTER_FLOATS;
            float cornerX = emitters.values[base + CORNER_X];
            float cornerY = emitters.values[base + CORNER_Y];
            float cornerZ = emitters.values[base + CORNER_Z];
            float edgeOneX = emitters.values[base + EDGE_ONE_X];
            float edgeOneY = emitters.values[base + EDGE_ONE_Y];
            float edgeOneZ = emitters.values[base + EDGE_ONE_Z];
            float edgeTwoX = emitters.values[base + EDGE_TWO_X];
            float edgeTwoY = emitters.values[base + EDGE_TWO_Y];
            float edgeTwoZ = emitters.values[base + EDGE_TWO_Z];
            float secondX = cornerX + edgeOneX;
            float secondY = cornerY + edgeOneY;
            float secondZ = cornerZ + edgeOneZ;
            float thirdX = cornerX + edgeTwoX;
            float thirdY = cornerY + edgeTwoY;
            float thirdZ = cornerZ + edgeTwoZ;
            float minX = Math.min(cornerX, Math.min(secondX, thirdX));
            float minY = Math.min(cornerY, Math.min(secondY, thirdY));
            float minZ = Math.min(cornerZ, Math.min(secondZ, thirdZ));
            float maxX = Math.max(cornerX, Math.max(secondX, thirdX));
            float maxY = Math.max(cornerY, Math.max(secondY, thirdY));
            float maxZ = Math.max(cornerZ, Math.max(secondZ, thirdZ));
            EmissionDistribution.SpatialMoments moments = distributions.get(
                    emitters.metadata[index * EMITTER_INTS + DISTRIBUTION]).spatialMoments();
            float centerX = cornerX + edgeOneX * moments.meanU() + edgeTwoX * moments.meanV();
            float centerY = cornerY + edgeOneY * moments.meanU() + edgeTwoY * moments.meanV();
            float centerZ = cornerZ + edgeOneZ * moments.meanU() + edgeTwoZ * moments.meanV();
            leaves.add(
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    centerX,
                    centerY,
                    centerZ,
                    emitters.values[base + POWER],
                    index,
                    LightDirection.fromNormal(
                            emitters.values[base + NORMAL_X],
                            emitters.values[base + NORMAL_Y],
                            emitters.values[base + NORMAL_Z],
                            (emitters.metadata[index * EMITTER_INTS + FLAGS]
                                            & EMITTER_FLAG_TWO_SIDED)
                                    != 0));
        }
        CpuLightTree.Result tree = CpuLightTree.buildOwned(leaves, emitters.size);
        return new CpuSectionLights(emitters, distributions, tree);
    }

    /** A white level-15 texel evaluates to the shared physical block-light ABI baseline. */
    static float emissionScale(int level) {
        int clamped = Math.max(0, Math.min(level, 15));
        return (float) clamped * clamped
                * dev.prime.render.shader.ShaderAbi.LEVEL_15_BLOCK_INTENSITY
                / (15.0F * 15.0F);
    }

    record Summary(
            int emitterCount,
            CpuLightTree.Bounds bounds,
            float power,
            int packedDirection) {
        private static final Summary EMPTY = new Summary(0, null, 0.0F, LightDirection.FULL);

        boolean isEmpty() {
            return this.emitterCount == 0;
        }
    }

    record Translated(CpuSectionLights lights, float x, float y, float z) {
        Translated {
            if (lights == null) {
                throw new IllegalArgumentException("Translated lights must not be null");
            }
        }
    }

    private static final class Emitters {
        private float[] values;
        private int[] metadata;
        private int size;

        private Emitters(int capacity) {
            this.values = new float[Math.multiplyExact(capacity, EMITTER_FLOATS)];
            this.metadata = new int[Math.multiplyExact(capacity, EMITTER_INTS)];
        }

        private void add(
                float cornerX,
                float cornerY,
                float cornerZ,
                float edgeOneX,
                float edgeOneY,
                float edgeOneZ,
                float edgeTwoX,
                float edgeTwoY,
                float edgeTwoZ,
                float normalX,
                float normalY,
                float normalZ,
                float area,
                float emissionScale,
                float power,
                int packedUv0,
                int packedUv1,
                int packedUv2,
                int packedTint,
                int distributionIndex,
                int flags,
                int textureId) {
            ensureCapacity();
            int floatBase = this.size * EMITTER_FLOATS;
            int intBase = this.size * EMITTER_INTS;
            this.values[floatBase + CORNER_X] = cornerX;
            this.values[floatBase + CORNER_Y] = cornerY;
            this.values[floatBase + CORNER_Z] = cornerZ;
            this.values[floatBase + AREA] = area;
            this.values[floatBase + EDGE_ONE_X] = edgeOneX;
            this.values[floatBase + EDGE_ONE_Y] = edgeOneY;
            this.values[floatBase + EDGE_ONE_Z] = edgeOneZ;
            this.values[floatBase + EMISSION_SCALE] = emissionScale;
            this.values[floatBase + EDGE_TWO_X] = edgeTwoX;
            this.values[floatBase + EDGE_TWO_Y] = edgeTwoY;
            this.values[floatBase + EDGE_TWO_Z] = edgeTwoZ;
            this.values[floatBase + POWER] = power;
            this.values[floatBase + NORMAL_X] = normalX;
            this.values[floatBase + NORMAL_Y] = normalY;
            this.values[floatBase + NORMAL_Z] = normalZ;
            this.metadata[intBase + UV_0] = packedUv0;
            this.metadata[intBase + UV_1] = packedUv1;
            this.metadata[intBase + UV_2] = packedUv2;
            this.metadata[intBase + TINT] = packedTint;
            this.metadata[intBase + DISTRIBUTION] = distributionIndex;
            this.metadata[intBase + FLAGS] = flags;
            this.metadata[intBase + TEXTURE_ID] = textureId;
            this.size++;
        }

        private void addTranslatedTo(
                Emitters destination,
                int emitter,
                float x,
                float y,
                float z,
                int[] distributionRemap) {
            int floatBase = emitter * EMITTER_FLOATS;
            int intBase = emitter * EMITTER_INTS;
            destination.add(
                    this.values[floatBase + CORNER_X] + x,
                    this.values[floatBase + CORNER_Y] + y,
                    this.values[floatBase + CORNER_Z] + z,
                    this.values[floatBase + EDGE_ONE_X],
                    this.values[floatBase + EDGE_ONE_Y],
                    this.values[floatBase + EDGE_ONE_Z],
                    this.values[floatBase + EDGE_TWO_X],
                    this.values[floatBase + EDGE_TWO_Y],
                    this.values[floatBase + EDGE_TWO_Z],
                    this.values[floatBase + NORMAL_X],
                    this.values[floatBase + NORMAL_Y],
                    this.values[floatBase + NORMAL_Z],
                    this.values[floatBase + AREA],
                    this.values[floatBase + EMISSION_SCALE],
                    this.values[floatBase + POWER],
                    this.metadata[intBase + UV_0],
                    this.metadata[intBase + UV_1],
                    this.metadata[intBase + UV_2],
                    this.metadata[intBase + TINT],
                    distributionRemap[this.metadata[intBase + DISTRIBUTION]],
                    this.metadata[intBase + FLAGS],
                    this.metadata[intBase + TEXTURE_ID]);
        }

        private void ensureCapacity() {
            if (this.size * EMITTER_FLOATS < this.values.length) {
                return;
            }
            int capacity = Math.max(16, Math.multiplyExact(this.size, 2));
            this.values = Arrays.copyOf(
                    this.values, Math.multiplyExact(capacity, EMITTER_FLOATS));
            this.metadata = Arrays.copyOf(
                    this.metadata, Math.multiplyExact(capacity, EMITTER_INTS));
        }
    }
}
