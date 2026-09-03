package dev.prime.render.vulkan.terrain;

import dev.prime.render.terrain.CpuVoxelInstances;
import dev.prime.render.terrain.TintIdResolver;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/** Immutable voxel instance stream after exact source tints have become renderer TintIds. */
record ResolvedVoxelInstances(CpuVoxelInstances source, int[] tintIds) {
    static final ResolvedVoxelInstances EMPTY =
            new ResolvedVoxelInstances(CpuVoxelInstances.EMPTY, new int[0]);

    ResolvedVoxelInstances {
        Objects.requireNonNull(source, "source");
        tintIds = Objects.requireNonNull(tintIds, "tintIds").clone();
        if (source.count() != tintIds.length) {
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
        return new ResolvedVoxelInstances(source, tintIds);
    }

    int count() {
        return this.source.count();
    }

    int meshIndex(int index) {
        return this.source.meshIndex(index);
    }

    int tintId(int index) {
        return this.tintIds[index];
    }

    float translationX(int index) {
        return this.source.translationX(index);
    }

    float translationY(int index) {
        return this.source.translationY(index);
    }

    float translationZ(int index) {
        return this.source.translationZ(index);
    }
}
