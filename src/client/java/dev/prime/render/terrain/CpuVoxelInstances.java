package dev.prime.render.terrain;

import java.util.Arrays;
import java.util.Objects;

/** Compact immutable instance stream for reusable or unique auxiliary BLAS geometry. */
public final class CpuVoxelInstances {
    public static final CpuVoxelInstances EMPTY =
            new CpuVoxelInstances(
                    new int[0], new int[0], new float[0], new float[0], new boolean[0]);

    private final int[] meshIndices;
    private final int[] packedTints;
    private final float[] translations;
    private final float[] previousTranslations;
    private final boolean[] motion;

    public CpuVoxelInstances(
            int[] meshIndices, int[] packedTints, float[] translations) {
        this(
                meshIndices,
                packedTints,
                translations,
                translations,
                new boolean[0]);
    }

    public CpuVoxelInstances(
            int[] meshIndices,
            int[] packedTints,
            float[] translations,
            float[] previousTranslations,
            boolean[] motion) {
        this.meshIndices = Objects.requireNonNull(meshIndices, "meshIndices");
        this.packedTints = Objects.requireNonNull(packedTints, "packedTints");
        this.translations = Objects.requireNonNull(translations, "translations");
        this.previousTranslations = Objects.requireNonNull(
                previousTranslations, "previousTranslations");
        this.motion = Objects.requireNonNull(motion, "motion");
        if (packedTints.length != meshIndices.length
                || translations.length != Math.multiplyExact(meshIndices.length, 3)
                || previousTranslations.length != translations.length
                || (motion.length != 0 && motion.length != meshIndices.length)) {
            throw new IllegalArgumentException(
                    "Instanced geometry arrays have inconsistent lengths");
        }
        for (float translation : translations) {
            if (!Float.isFinite(translation)) {
                throw new IllegalArgumentException(
                        "Instance translation must be finite");
            }
        }
        for (float translation : previousTranslations) {
            if (!Float.isFinite(translation)) {
                throw new IllegalArgumentException(
                        "Previous instance translation must be finite");
            }
        }
        for (int packedTint : packedTints) {
            if ((packedTint & 0xff00_0000) != 0) {
                throw new IllegalArgumentException(
                        "Voxel-surface instance tint exceeds packed RGB");
            }
        }
    }

    /** Borrowed read-only mesh-index storage. */
    public int[] meshIndices() {
        return this.meshIndices;
    }

    /** Borrowed read-only packed-tint storage. */
    public int[] packedTints() {
        return this.packedTints;
    }

    /** Borrowed read-only xyz triplets. */
    public float[] translations() {
        return this.translations;
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
        return this.translations[Math.multiplyExact(index, 3)];
    }

    public float translationY(int index) {
        return this.translations[Math.multiplyExact(index, 3) + 1];
    }

    public float translationZ(int index) {
        return this.translations[Math.multiplyExact(index, 3) + 2];
    }

    public boolean hasMotion(int index) {
        return this.motion.length != 0 && this.motion[index];
    }

    public float previousTranslationX(int index) {
        return this.previousTranslations[Math.multiplyExact(index, 3)];
    }

    public float previousTranslationY(int index) {
        return this.previousTranslations[Math.multiplyExact(index, 3) + 1];
    }

    public float previousTranslationZ(int index) {
        return this.previousTranslations[Math.multiplyExact(index, 3) + 2];
    }

    public long motionByteSize() {
        return this.motion.length == 0
                ? 0L
                : Math.multiplyExact((long) this.count(), 3L * Float.BYTES + 1L);
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
