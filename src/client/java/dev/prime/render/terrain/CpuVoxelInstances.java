// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import java.util.Arrays;
import java.util.Objects;

/** Compact immutable instance stream for reusable or unique auxiliary BLAS geometry. */
public final class CpuVoxelInstances {
    public static final int TRANSFORM_WORDS = 12;
    public static final CpuVoxelInstances EMPTY =
            transformed(new int[0], new int[0], new float[0], new float[0], new boolean[0]);

    private final int[] meshIndices;
    private final int[] packedTints;
    private final float[] transforms;
    private final float[] previousTransforms;
    private final boolean[] motion;

    public CpuVoxelInstances(
            int[] meshIndices, int[] packedTints, float[] translations) {
        this(
                meshIndices,
                packedTints,
                translationTransforms(translations),
                translationTransforms(translations),
                new boolean[0]);
    }

    private CpuVoxelInstances(
            int[] meshIndices,
            int[] packedTints,
            float[] transforms,
            float[] previousTransforms,
            boolean[] motion) {
        this.meshIndices = Objects.requireNonNull(meshIndices, "meshIndices");
        this.packedTints = Objects.requireNonNull(packedTints, "packedTints");
        this.transforms = Objects.requireNonNull(transforms, "transforms");
        this.previousTransforms = Objects.requireNonNull(
                previousTransforms, "previousTransforms");
        this.motion = Objects.requireNonNull(motion, "motion");
        if (packedTints.length != meshIndices.length
                || transforms.length
                        != Math.multiplyExact(meshIndices.length, TRANSFORM_WORDS)
                || previousTransforms.length != transforms.length
                || (motion.length != 0 && motion.length != meshIndices.length)) {
            throw new IllegalArgumentException(
                    "Instanced geometry arrays have inconsistent lengths");
        }
        for (float value : transforms) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Instance transform must be finite");
            }
        }
        for (float value : previousTransforms) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Previous instance transform must be finite");
            }
        }
        for (int packedTint : packedTints) {
            if ((packedTint & 0xff00_0000) != 0) {
                throw new IllegalArgumentException(
                        "Voxel-surface instance tint exceeds packed RGB");
            }
        }
    }

    public static CpuVoxelInstances transformed(
            int[] meshIndices,
            int[] packedTints,
            float[] transforms,
            float[] previousTransforms,
            boolean[] motion) {
        return new CpuVoxelInstances(
                meshIndices, packedTints, transforms, previousTransforms, motion);
    }

    public static CpuVoxelInstances translated(
            int[] meshIndices,
            int[] packedTints,
            float[] translations,
            float[] previousTranslations,
            boolean[] motion) {
        return transformed(
                meshIndices,
                packedTints,
                translationTransforms(translations),
                translationTransforms(previousTranslations),
                motion);
    }

    /** Borrowed read-only mesh-index storage. */
    public int[] meshIndices() {
        return this.meshIndices;
    }

    /** Borrowed read-only packed-tint storage. */
    public int[] packedTints() {
        return this.packedTints;
    }

    /** Borrowed read-only row-major Vulkan 3x4 transforms. */
    public float[] transforms() {
        return this.transforms;
    }

    /** Borrowed read-only previous row-major Vulkan 3x4 transforms. */
    public float[] previousTransforms() {
        return this.previousTransforms;
    }

    /** Allocates xyz translations for compatibility diagnostics and tests. */
    public float[] translations() {
        float[] result = new float[Math.multiplyExact(this.count(), 3)];
        for (int index = 0; index < this.count(); index++) {
            result[index * 3] = this.translationX(index);
            result[index * 3 + 1] = this.translationY(index);
            result[index * 3 + 2] = this.translationZ(index);
        }
        return result;
    }

    public int count() {
        return this.meshIndices.length;
    }

    public int meshIndex(int index) {
        return this.meshIndices[index];
    }

    public int packedTint(int index) {
        return this.packedTints[index];
    }

    public float translationX(int index) {
        return this.transforms[transformOffset(index) + 3];
    }

    public float translationY(int index) {
        return this.transforms[transformOffset(index) + 7];
    }

    public float translationZ(int index) {
        return this.transforms[transformOffset(index) + 11];
    }

    public boolean hasMotion(int index) {
        return this.motion.length != 0 && this.motion[index];
    }

    public float previousTranslationX(int index) {
        return this.previousTransforms[transformOffset(index) + 3];
    }

    public float previousTranslationY(int index) {
        return this.previousTransforms[transformOffset(index) + 7];
    }

    public float previousTranslationZ(int index) {
        return this.previousTransforms[transformOffset(index) + 11];
    }

    public float transform(int index, int row, int column) {
        return this.transforms[matrixIndex(index, row, column)];
    }

    public float previousTransform(int index, int row, int column) {
        return this.previousTransforms[matrixIndex(index, row, column)];
    }

    public boolean hasAffineLinearTransform(int index) {
        return !identityLinear(this.transforms, transformOffset(index))
                || !identityLinear(this.previousTransforms, transformOffset(index));
    }

    public long motionByteSize() {
        return this.motion.length == 0
                ? 0L
                : Math.multiplyExact(
                        (long) this.count(), (long) TRANSFORM_WORDS * Float.BYTES + 1L);
    }

    private static float[] translationTransforms(float[] translations) {
        Objects.requireNonNull(translations, "translations");
        if (translations.length % 3 != 0) {
            throw new IllegalArgumentException(
                    "Instance translation array must contain xyz triplets");
        }
        float[] result = new float[Math.multiplyExact(
                translations.length / 3, TRANSFORM_WORDS)];
        for (int index = 0; index < translations.length / 3; index++) {
            int source = index * 3;
            int target = index * TRANSFORM_WORDS;
            result[target] = 1.0F;
            result[target + 3] = translations[source];
            result[target + 5] = 1.0F;
            result[target + 7] = translations[source + 1];
            result[target + 10] = 1.0F;
            result[target + 11] = translations[source + 2];
        }
        return result;
    }

    private static int transformOffset(int index) {
        return Math.multiplyExact(index, TRANSFORM_WORDS);
    }

    private static int matrixIndex(int index, int row, int column) {
        if (row < 0 || row >= 3 || column < 0 || column >= 4) {
            throw new IndexOutOfBoundsException("3x4 transform index is outside the matrix");
        }
        return transformOffset(index) + row * 4 + column;
    }

    private static boolean identityLinear(float[] values, int offset) {
        return values[offset] == 1.0F
                && values[offset + 1] == 0.0F
                && values[offset + 2] == 0.0F
                && values[offset + 4] == 0.0F
                && values[offset + 5] == 1.0F
                && values[offset + 6] == 0.0F
                && values[offset + 8] == 0.0F
                && values[offset + 9] == 0.0F
                && values[offset + 10] == 1.0F;
    }

    static final class Builder {
        private int[] meshIndices = new int[256];
        private int[] packedTints = new int[256];
        private float[] translations = new float[256 * 3];
        private int size;

        void add(int meshIndex, int packedTint, float x, float y, float z) {
            if (meshIndex < 0) {
                throw new IllegalArgumentException(
                        "Voxel-surface mesh index must not be negative");
            }
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException(
                        "Voxel-surface instance translation must be finite");
            }
            this.ensure(1);
            this.meshIndices[this.size] = meshIndex;
            this.packedTints[this.size] = packedTint;
            int translation = this.size * 3;
            this.translations[translation] = x;
            this.translations[translation + 1] = y;
            this.translations[translation + 2] = z;
            this.size++;
        }

        CpuVoxelInstances build() {
            return this.size == 0
                    ? EMPTY
                    : new CpuVoxelInstances(
                            Arrays.copyOf(this.meshIndices, this.size),
                            Arrays.copyOf(this.packedTints, this.size),
                            Arrays.copyOf(this.translations, this.size * 3));
        }

        private void ensure(int count) {
            if (this.size + count <= this.meshIndices.length) {
                return;
            }
            int next = Math.max(this.meshIndices.length * 2, this.size + count);
            this.meshIndices = Arrays.copyOf(this.meshIndices, next);
            this.packedTints = Arrays.copyOf(this.packedTints, next);
            this.translations = Arrays.copyOf(this.translations, next * 3);
        }
    }
}
