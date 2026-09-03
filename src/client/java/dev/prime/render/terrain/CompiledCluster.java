package dev.prime.render.terrain;

import java.util.Objects;
import net.minecraft.core.SectionPos;

/**
 * Immutable CPU result of compiling one logical cluster.
 *
 * <p>Its mesh arrays transfer ownership from the compiler to the scene residency boundary and
 * are never mutated after publication.
 */
public record CompiledCluster(
        long key,
        CpuClusterMesh mesh,
        boolean dynamic,
        float[] motionPositions) {
    private static final float[] NO_MOTION = new float[0];

    public CompiledCluster(
            long key,
            CpuClusterMesh mesh) {
        this(key, mesh, false, NO_MOTION);
    }

    public CompiledCluster {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(motionPositions, "motionPositions");
        if (SectionCluster.origin(SectionPos.x(key)) != SectionPos.x(key)
                || SectionCluster.origin(SectionPos.y(key)) != SectionPos.y(key)
                || SectionCluster.origin(SectionPos.z(key)) != SectionPos.z(key)) {
            throw new IllegalArgumentException(
                    "Compiled cluster key must identify an aligned cluster");
        }
        long expectedMotionWords = dynamic ? mesh.triangleLayout().triangleCount() * 9L : 0L;
        if (motionPositions.length != expectedMotionWords) {
            throw new IllegalArgumentException(
                    "Dynamic motion payload does not match the compiled cluster");
        }
    }

    public static CompiledCluster dynamic(
            int clusterX,
            int clusterY,
            int clusterZ,
            CpuClusterMesh mesh,
            float[] motionPositions) {
        return new CompiledCluster(
                SectionPos.asLong(clusterX, clusterY, clusterZ),
                mesh,
                true,
                motionPositions);
    }

    public int clusterX() { return SectionPos.x(this.key); }
    public int clusterY() { return SectionPos.y(this.key); }
    public int clusterZ() { return SectionPos.z(this.key); }

    public boolean isEmpty() {
        return this.mesh.isEmpty();
    }
}
