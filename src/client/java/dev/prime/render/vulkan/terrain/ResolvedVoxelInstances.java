package dev.prime.render.vulkan.terrain;

import dev.prime.render.terrain.CpuVoxelInstances;
import dev.prime.render.terrain.TintIdResolver;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/** Immutable voxel instance stream after exact source tints have become renderer TintIds. */
record ResolvedVoxelInstances(int[] meshIndices, int[] tintIds, float[] translations) {
    static final ResolvedVoxelInstances EMPTY =
            new ResolvedVoxelInstances(new int[0], new int[0], new float[0]);

    ResolvedVoxelInstances {
        meshIndices = Objects.requireNonNull(meshIndices, "meshIndices").clone();
        tintIds = Objects.requireNonNull(tintIds, "tintIds").clone();
        translations = Objects.requireNonNull(translations, "translations").clone();
        if (meshIndices.length != tintIds.length
                || translations.length != Math.multiplyExact(meshIndices.length, 3)) {
            throw new IllegalArgumentException(
                    "Resolved voxel instance arrays have inconsistent lengths");
        }
    }

    static ResolvedVoxelInstances resolve(
            CpuVoxelInstances source, IntUnaryOperator resolver) {
        Objects.requireNonNull(source, "source");
        if (source.count() == 0) {
            return EMPTY;
        }
        int[] tintIds = new int[source.count()];
        for (int index = 0; index < tintIds.length; index++) {
            tintIds[index] = TintIdResolver.resolveOpaquePackedRgb(
                    source.packedTint(index), resolver);
        }
        return new ResolvedVoxelInstances(
                source.meshIndices(), tintIds, source.translations());
    }

    int count() {
        return this.meshIndices.length;
    }

    int meshIndex(int index) {
        return this.meshIndices[index];
    }

    int tintId(int index) {
        return this.tintIds[index];
    }

    float translationX(int index) {
        return this.translations[index * 3];
    }

    float translationY(int index) {
        return this.translations[index * 3 + 1];
    }

    float translationZ(int index) {
        return this.translations[index * 3 + 2];
    }
}
