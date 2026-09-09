// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpritePixelView;
import java.util.Arrays;

/** A resolution-independent, surface-local importance distribution for an emissive triangle. */
final class EmissionDistribution {
    static final int SUBDIVISION = 16;
    static final int CELL_COUNT = SUBDIVISION * SUBDIVISION;
    private static final float MINIMUM_CELL_IMPORTANCE = 1.0E-5F;
    private static final int STRATIFIED_SAMPLE_COUNT = 4;
    private static final float[] SRGB_TO_LINEAR = createSrgbToLinearTable();
    private static final Cell[] CELLS = createCells();
    private static final ThreadLocal<BuildWorkspace> BUILD_WORKSPACE =
            ThreadLocal.withInitial(BuildWorkspace::new);
    private static final EmissionDistribution UNIFORM = createUniform();

    private final float[] aliasProbabilities;
    private final int[] aliases;
    private final float[] probabilityMasses;
    private final float meanImportance;
    private final boolean hasSourceSupport;
    private final SpatialMoments spatialMoments;
    private final int gpuTableHash;

    private EmissionDistribution(
            float[] weights, BuildWorkspace workspace, boolean hasSourceSupport) {
        this.aliasProbabilities = new float[CELL_COUNT];
        this.aliases = new int[CELL_COUNT];
        this.probabilityMasses = new float[CELL_COUNT];
        double sum = 0.0;
        for (float weight : weights) {
            sum += weight;
        }
        if (!(sum > 0.0) || !Double.isFinite(sum)) {
            throw new IllegalArgumentException("Emission importance weights must have positive finite mass");
        }
        this.meanImportance = (float) (sum / CELL_COUNT);
        this.hasSourceSupport = hasSourceSupport;
        buildAliasTable(
                weights,
                sum,
                this.aliasProbabilities,
                this.aliases,
                this.probabilityMasses,
                workspace);
        this.spatialMoments = spatialMoments(this.probabilityMasses);
        int hash = Arrays.hashCode(this.aliasProbabilities);
        hash = 31 * hash + Arrays.hashCode(this.aliases);
        this.gpuTableHash = 31 * hash + Arrays.hashCode(this.probabilityMasses);
    }

    static EmissionDistribution build(Key key) {
        if (key.sprite == null || key.sprite.pixelView() == null) {
            return UNIFORM;
        }
        BuildWorkspace workspace = BUILD_WORKSPACE.get();
        float[] weights = workspace.weights;
        fillTextureImportance(key, weights, workspace);
        boolean hasSourceSupport = false;
        for (float weight : weights) {
            hasSourceSupport |= weight > 0.0F;
        }
        for (int index = 0; index < weights.length; index++) {
            weights[index] = Math.max(weights[index], MINIMUM_CELL_IMPORTANCE);
        }
        return new EmissionDistribution(weights, workspace, hasSourceSupport);
    }

    static EmissionDistribution uniform() {
        return UNIFORM;
    }

    private static EmissionDistribution createUniform() {
        float[] weights = new float[CELL_COUNT];
        java.util.Arrays.fill(weights, 1.0F);
        return new EmissionDistribution(weights, new BuildWorkspace(), true);
    }

    private static void fillTextureImportance(
            Key key, float[] weights, BuildWorkspace workspace) {
        CapturedSprite sprite = key.sprite;
        SpritePixelView pixels = sprite.pixelView();
        if (pixels == null) {
            throw new IllegalStateException("Emission pixels were not checked before sampling");
        }
        int frameCount = sprite.uniqueFrameCount();
        int contentWidth = sprite.frameWidth();
        int contentHeight = sprite.frameHeight();
        int columns = Math.max(pixels.imageWidth() / contentWidth, 1);
        float triangleU0 = key.u0();
        float triangleV0 = key.v0();
        float triangleU1 = key.u1();
        float triangleV1 = key.v1();
        float triangleU2 = key.u2();
        float triangleV2 = key.v2();
        float[] tint = workspace.tint;
        fillLinearTint(key.tintArgb, tint);
        float[] barycentric = workspace.barycentric;
        for (int cellIndex = 0; cellIndex < CELL_COUNT; cellIndex++) {
            Cell cell = cell(cellIndex);
            float total = 0.0F;
            for (int sample = 0; sample < STRATIFIED_SAMPLE_COUNT; sample++) {
                cell.samplePoint(sample, barycentric);
                float localU = clampUnit(interpolate(
                        triangleU0, triangleU1, triangleU2, barycentric));
                float localV = clampUnit(interpolate(
                        triangleV0, triangleV1, triangleV2, barycentric));
                int pixelX = Math.min((int) (localU * contentWidth), contentWidth - 1);
                int pixelY = Math.min((int) (localV * contentHeight), contentHeight - 1);
                float maximum = 0.0F;
                for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                    int frame = sprite.uniqueFrame(frameIndex);
                    int sourceX = frame % columns * contentWidth + pixelX;
                    int sourceY = frame / columns * contentHeight + pixelY;
                    int argb = pixels.argb(sourceX, sourceY);
                    int alpha = argb >>> 24;
                    if (key.cutout && alpha < 128) {
                        continue;
                    }
                    float red = SRGB_TO_LINEAR[argb >>> 16 & 0xff] * tint[0];
                    float green = SRGB_TO_LINEAR[argb >>> 8 & 0xff] * tint[1];
                    float blue = SRGB_TO_LINEAR[argb & 0xff] * tint[2];
                    float authoredEmission = key.emission == null
                            ? 0.0F
                            : key.emission.sample(frame, localU, localV);
                    // An authored LabPBR alpha channel replaces Minecraft's ordinal block-light
                    // value. In particular, alpha 0 can deliberately turn a vanilla emitter off.
                    float emission = key.emission == null
                            ? key.vanillaEmissionFraction
                            : authoredEmission;
                    // Importance uses the largest component in Prime's actual linear Rec.2020
                    // working space. This controls variance only: emitted RGB and its PDF remain
                    // separate, so changing this proxy cannot bias the estimator.
                    maximum = Math.max(
                            maximum,
                            linearSrgbToRec2020Maximum(red, green, blue) * emission);
                }
                total += maximum;
            }
            weights[cellIndex] = total / STRATIFIED_SAMPLE_COUNT;
        }
    }

    private static void fillLinearTint(int argb, float[] target) {
        target[0] = SRGB_TO_LINEAR[argb >>> 16 & 0xff];
        target[1] = SRGB_TO_LINEAR[argb >>> 8 & 0xff];
        target[2] = SRGB_TO_LINEAR[argb & 0xff];
    }

    private static float decodeSrgb(float value) {
        return value <= 0.04045F
                ? value / 12.92F
                : (float) Math.pow((value + 0.055F) / 1.055F, 2.4);
    }

    private static float[] createSrgbToLinearTable() {
        float[] result = new float[256];
        for (int index = 0; index < result.length; index++) {
            result[index] = decodeSrgb(index / 255.0F);
        }
        return result;
    }

    static float linearSrgbToRec2020Maximum(float red, float green, float blue) {
        // Keep these coefficients identical to primeLinearSrgbToLinearRec2020 in math/color_space.slang.
        float rec2020Red = 0.6274039F * red + 0.3292830F * green + 0.0433131F * blue;
        float rec2020Green = 0.0690973F * red + 0.9195404F * green + 0.0113623F * blue;
        float rec2020Blue = 0.0163914F * red + 0.0880133F * green + 0.8955953F * blue;
        return Math.max(rec2020Red, Math.max(rec2020Green, rec2020Blue));
    }

    private static float interpolate(float first, float second, float third, float[] barycentric) {
        return first * barycentric[0] + second * barycentric[1] + third * barycentric[2];
    }

    private static float clampUnit(float value) {
        return Math.max(0.0F, Math.min(Math.nextDown(1.0F), value));
    }

    private static void buildAliasTable(
            float[] weights,
            double sum,
            float[] probabilities,
            int[] aliases,
            float[] masses,
            BuildWorkspace workspace) {
        double[] scaled = workspace.scaled;
        int[] small = workspace.small;
        int[] large = workspace.large;
        int smallSize = 0;
        int largeSize = 0;
        for (int index = 0; index < CELL_COUNT; index++) {
            masses[index] = (float) (weights[index] / sum);
            scaled[index] = weights[index] * CELL_COUNT / sum;
            if (scaled[index] < 1.0) {
                small[smallSize++] = index;
            } else {
                large[largeSize++] = index;
            }
        }
        while (smallSize != 0 && largeSize != 0) {
            int smallIndex = small[--smallSize];
            int largeIndex = large[--largeSize];
            probabilities[smallIndex] = (float) scaled[smallIndex];
            aliases[smallIndex] = largeIndex;
            scaled[largeIndex] = scaled[largeIndex] + scaled[smallIndex] - 1.0;
            if (scaled[largeIndex] < 1.0) {
                small[smallSize++] = largeIndex;
            } else {
                large[largeSize++] = largeIndex;
            }
        }
        while (largeSize != 0) {
            int index = large[--largeSize];
            probabilities[index] = 1.0F;
            aliases[index] = index;
        }
        while (smallSize != 0) {
            int index = small[--smallSize];
            probabilities[index] = 1.0F;
            aliases[index] = index;
        }
    }

    static Cell cell(int index) {
        if (index < 0 || index >= CELL_COUNT) {
            throw new IndexOutOfBoundsException(index);
        }
        return CELLS[index];
    }

    private static Cell[] createCells() {
        Cell[] result = new Cell[CELL_COUNT];
        for (int index = 0; index < result.length; index++) {
            result[index] = decodeCell(index);
        }
        return result;
    }

    private static Cell decodeCell(int index) {
        int remaining = index;
        for (int row = 0; row < SUBDIVISION; row++) {
            int rowCount = 2 * (SUBDIVISION - row) - 1;
            if (remaining < rowCount) {
                int column = remaining / 2;
                boolean upper = (remaining & 1) != 0;
                return new Cell(column, row, upper);
            }
            remaining -= rowCount;
        }
        throw new IllegalStateException("Emission cell index mapping failed");
    }

    private static final class BuildWorkspace {
        private final float[] weights = new float[CELL_COUNT];
        private final double[] scaled = new double[CELL_COUNT];
        private final int[] small = new int[CELL_COUNT];
        private final int[] large = new int[CELL_COUNT];
        private final float[] barycentric = new float[3];
        private final float[] tint = new float[3];
    }

    float aliasProbability(int index) {
        return this.aliasProbabilities[index];
    }

    int alias(int index) {
        return this.aliases[index];
    }

    static int packAliasGeometry(int alias, int cellIndex) {
        if (alias < 0 || alias >= CELL_COUNT) {
            throw new IllegalArgumentException("Emission alias index is outside the 8-bit cell table");
        }
        return alias | cell(cellIndex).packedGeometry() << 8;
    }

    float probabilityMass(int index) {
        return this.probabilityMasses[index];
    }

    float meanImportance() {
        return this.meanImportance;
    }

    SpatialMoments spatialMoments() {
        return this.spatialMoments;
    }

    boolean hasSourceSupport() {
        return this.hasSourceSupport;
    }

    /** GPU tables with identical bits are interchangeable even when their source sprites differ. */
    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof EmissionDistribution distribution
                        && Arrays.equals(this.aliasProbabilities, distribution.aliasProbabilities)
                        && Arrays.equals(this.aliases, distribution.aliases)
                        && Arrays.equals(this.probabilityMasses, distribution.probabilityMasses);
    }

    @Override
    public int hashCode() {
        return this.gpuTableHash;
    }

    record Key(
            CapturedSprite sprite,
            int packedUv0,
            int packedUv1,
            int packedUv2,
            int tintArgb,
            boolean cutout,
            float vanillaEmissionFraction,
            LabPbrEmissionMap emission) {
        float u0() {
            return PrimitivePacking.unpackUv(this.packedUv0, false);
        }

        float v0() {
            return PrimitivePacking.unpackUv(this.packedUv0, true);
        }

        float u1() {
            return PrimitivePacking.unpackUv(this.packedUv1, false);
        }

        float v1() {
            return PrimitivePacking.unpackUv(this.packedUv1, true);
        }

        float u2() {
            return PrimitivePacking.unpackUv(this.packedUv2, false);
        }

        float v2() {
            return PrimitivePacking.unpackUv(this.packedUv2, true);
        }
    }

    record Cell(int column, int row, boolean upper) {
        int packedGeometry() {
            return this.column | this.row << 4 | (this.upper ? 1 << 8 : 0);
        }

        float[][] vertices() {
            float inverse = 1.0F / SUBDIVISION;
            float x = this.column * inverse;
            float y = this.row * inverse;
            if (this.upper) {
                return new float[][] {
                    {1.0F - x - inverse - y, x + inverse, y},
                    {1.0F - x - 2.0F * inverse - y, x + inverse, y + inverse},
                    {1.0F - x - y - inverse, x, y + inverse}
                };
            }
            return new float[][] {
                {1.0F - x - y, x, y},
                {1.0F - x - inverse - y, x + inverse, y},
                {1.0F - x - y - inverse, x, y + inverse}
            };
        }

        void samplePoint(int sampleIndex, float[] target) {
            if (sampleIndex < 0 || sampleIndex >= STRATIFIED_SAMPLE_COUNT) {
                throw new IndexOutOfBoundsException(sampleIndex);
            }
            if (target.length < 3) {
                throw new IllegalArgumentException("Barycentric output must contain three elements");
            }
            float inverse = 1.0F / SUBDIVISION;
            float x = this.column * inverse;
            float y = this.row * inverse;
            float centroid0;
            float centroid1;
            float centroid2;
            if (this.upper) {
                centroid0 = 1.0F - x - y - 4.0F * inverse / 3.0F;
                centroid1 = x + 2.0F * inverse / 3.0F;
                centroid2 = y + 2.0F * inverse / 3.0F;
            } else {
                centroid0 = 1.0F - x - y - 2.0F * inverse / 3.0F;
                centroid1 = x + inverse / 3.0F;
                centroid2 = y + inverse / 3.0F;
            }
            if (sampleIndex == 0) {
                target[0] = centroid0;
                target[1] = centroid1;
                target[2] = centroid2;
                return;
            }
            int vertex = sampleIndex - 1;
            float vertex1;
            float vertex2;
            if (this.upper) {
                vertex1 = vertex < 2 ? x + inverse : x;
                vertex2 = vertex == 0 ? y : y + inverse;
            } else {
                vertex1 = vertex == 1 ? x + inverse : x;
                vertex2 = vertex == 2 ? y + inverse : y;
            }
            float vertex0 = 1.0F - vertex1 - vertex2;
            target[0] = (centroid0 + vertex0) * 0.5F;
            target[1] = (centroid1 + vertex1) * 0.5F;
            target[2] = (centroid2 + vertex2) * 0.5F;
        }

        private void addSpatialMoments(double mass, double[] target) {
            double inverse = 1.0 / SUBDIVISION;
            double x = this.column * inverse;
            double y = this.row * inverse;
            double firstU;
            double firstV;
            double secondU;
            double secondV;
            double thirdU;
            double thirdV;
            if (this.upper) {
                firstU = x + inverse;
                firstV = y;
                secondU = x + inverse;
                secondV = y + inverse;
                thirdU = x;
                thirdV = y + inverse;
            } else {
                firstU = x;
                firstV = y;
                secondU = x + inverse;
                secondV = y;
                thirdU = x;
                thirdV = y + inverse;
            }
            double sumU = firstU + secondU + thirdU;
            double sumV = firstV + secondV + thirdV;
            target[0] += mass * sumU / 3.0;
            target[1] += mass * sumV / 3.0;
        }
    }

    private static SpatialMoments spatialMoments(float[] probabilityMasses) {
        double[] moments = new double[2];
        for (int index = 0; index < CELL_COUNT; index++) {
            cell(index).addSpatialMoments(probabilityMasses[index], moments);
        }
        return new SpatialMoments(
                (float) moments[0],
                (float) moments[1]);
    }

    record SpatialMoments(float meanU, float meanV) {}
}
