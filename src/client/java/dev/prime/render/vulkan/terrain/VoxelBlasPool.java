package dev.prime.render.vulkan.terrain;

import dev.prime.render.terrain.CpuMeshSegment;
import dev.prime.render.terrain.CpuVoxelMesh;
import dev.prime.render.terrain.OpacityMicromapData;
import dev.prime.render.vulkan.PreparedBlas;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Render-thread-owned prototype pool. Static and motion inputs have separate lifetime domains,
 * so a dynamic instance can never acquire a static BLAS whose input vertices have retired. */
final class VoxelBlasPool implements AutoCloseable {
    private final Map<Key, Entry> byContent = new HashMap<>();
    private final IdentityHashMap<PreparedBlas, Entry> byBlas = new IdentityHashMap<>();
    private boolean closed;

    PreparedBlas acquire(CpuVoxelMesh mesh, PreparedBlas.PositionLifetime lifetime,
            Supplier<PreparedBlas> factory) {
        this.requireOpen();
        if (!mesh.reusable()) {
            PreparedBlas blas = factory.get();
            Entry created = new Entry(null, blas);
            created.references = 1;
            if (this.byBlas.put(blas, created) != null) {
                throw new IllegalStateException("Unique BLAS factory returned a pooled instance");
            }
            return blas;
        }
        Key lookup = Key.lookup(mesh, lifetime);
        Entry existing = this.byContent.get(lookup);
        if (existing != null) {
            existing.references++;
            return existing.blas;
        }
        PreparedBlas blas = factory.get();
        Key key = lookup.snapshot();
        Entry created = new Entry(key, blas);
        created.references = 1;
        this.byContent.put(key, created);
        if (this.byBlas.put(blas, created) != null) {
            this.byContent.remove(key);
            throw new IllegalStateException("Voxel BLAS factory returned a pooled instance");
        }
        return blas;
    }

    /** Returns the resource only when the last cluster reference was released. */
    PreparedBlas release(PreparedBlas blas) {
        Entry entry = this.byBlas.get(blas);
        if (entry == null || entry.references <= 0) {
            throw new IllegalStateException("Voxel BLAS pool reference underflow");
        }
        entry.references--;
        if (entry.references != 0) {
            return null;
        }
        this.byBlas.remove(blas);
        if (entry.key != null && !this.byContent.remove(entry.key, entry)) {
            throw new IllegalStateException("Voxel BLAS pool lost a live entry");
        }
        return blas;
    }

    boolean hasOpacityMicromapBuild(PreparedBlas blas) {
        Entry entry = this.requireEntry(blas);
        return !entry.buildRecorded && blas.hasOpacityMicromapBuild();
    }

    void recordOpacityMicromapBuild(
            PreparedBlas blas, VkCommandBuffer commandBuffer) {
        Entry entry = this.requireEntry(blas);
        if (!entry.buildRecorded) {
            blas.recordOpacityMicromapBuild(commandBuffer);
        }
    }

    void recordBuild(PreparedBlas blas, VkCommandBuffer commandBuffer) {
        Entry entry = this.requireEntry(blas);
        if (entry.buildRecorded) {
            return;
        }
        blas.recordBuild(commandBuffer);
        entry.buildRecorded = true;
    }

    void submitted(PreparedBlas blas) {
        Entry entry = this.requireEntry(blas);
        if (entry.submitted) {
            return;
        }
        if (!entry.buildRecorded) {
            throw new IllegalStateException("Voxel BLAS was submitted before its build");
        }
        blas.onBuildSubmitted();
        blas.retireBuildResources();
        entry.submitted = true;
    }

    int entryCount() {
        return this.byContent.size();
    }

    @Override
    public void close() {
        this.closed = true;
        if (!this.byContent.isEmpty() || !this.byBlas.isEmpty()) {
            throw new IllegalStateException("Voxel BLAS pool closed with live references");
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Voxel BLAS pool is closed");
        }
    }

    private Entry requireEntry(PreparedBlas blas) {
        Entry entry = this.byBlas.get(blas);
        if (entry == null || entry.references <= 0) {
            throw new IllegalStateException("Voxel BLAS is not pooled");
        }
        return entry;
    }

    static final class Key {
        private final PreparedBlas.PositionLifetime lifetime;
        private final float[] positions;
        private final int[] primitives;
        private final int opaqueTriangles;
        private final int cutoutTriangles;
        private final int transmissiveTriangles;
        private final byte[] micromapBlocks;
        private final int[] micromapOffsets;
        private final int[] micromapFormats;
        private final int[] micromapLevels;
        private final int[] micromapIndices;
        private final int hash;

        Key(CpuVoxelMesh mesh) {
            this(mesh, PreparedBlas.PositionLifetime.BUILD_ONLY, true);
        }

        Key(CpuVoxelMesh mesh, PreparedBlas.PositionLifetime lifetime) {
            this(mesh, lifetime, true);
        }

        private Key(CpuVoxelMesh mesh, PreparedBlas.PositionLifetime lifetime, boolean snapshot) {
            this.lifetime = lifetime;
            CpuMeshSegment geometry = mesh.geometry();
            this.positions = snapshot ? geometry.positions().clone() : geometry.positions();
            this.primitives = snapshot
                    ? geometry.primitiveRecords().clone()
                    : geometry.primitiveRecords();
            this.opaqueTriangles = geometry.opaqueTriangleCount();
            this.cutoutTriangles = geometry.cutoutTriangleCount();
            this.transmissiveTriangles = geometry.transmissiveTriangleCount();
            OpacityMicromapData micromap = mesh.opacityMicromap();
            this.micromapBlocks = snapshot
                    ? micromap.blocks().clone()
                    : micromap.blocks();
            this.micromapOffsets = snapshot
                    ? micromap.blockOffsets().clone()
                    : micromap.blockOffsets();
            this.micromapFormats = snapshot
                    ? micromap.blockFormats().clone()
                    : micromap.blockFormats();
            this.micromapLevels = snapshot
                    ? micromap.blockSubdivisionLevels().clone()
                    : micromap.blockSubdivisionLevels();
            this.micromapIndices = snapshot
                    ? micromap.triangleIndices().clone()
                    : micromap.triangleIndices();
            this.hash = 31 * mesh.gpuContentHash() + lifetime.ordinal();
        }

        private Key(Key source) {
            this.lifetime = source.lifetime;
            this.positions = source.positions.clone();
            this.primitives = source.primitives.clone();
            this.opaqueTriangles = source.opaqueTriangles;
            this.cutoutTriangles = source.cutoutTriangles;
            this.transmissiveTriangles = source.transmissiveTriangles;
            this.micromapBlocks = source.micromapBlocks.clone();
            this.micromapOffsets = source.micromapOffsets.clone();
            this.micromapFormats = source.micromapFormats.clone();
            this.micromapLevels = source.micromapLevels.clone();
            this.micromapIndices = source.micromapIndices.clone();
            this.hash = source.hash;
        }

        static Key lookup(CpuVoxelMesh mesh, PreparedBlas.PositionLifetime lifetime) {
            return new Key(mesh, lifetime, false);
        }

        Key snapshot() {
            return new Key(this);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof Key key
                            && this.lifetime == key.lifetime
                            && this.opaqueTriangles == key.opaqueTriangles
                            && this.cutoutTriangles == key.cutoutTriangles
                            && this.transmissiveTriangles == key.transmissiveTriangles
                            && rawFloatEquals(this.positions, key.positions)
                            && Arrays.equals(this.primitives, key.primitives)
                            && Arrays.equals(this.micromapBlocks, key.micromapBlocks)
                            && Arrays.equals(this.micromapOffsets, key.micromapOffsets)
                            && Arrays.equals(this.micromapFormats, key.micromapFormats)
                            && Arrays.equals(this.micromapLevels, key.micromapLevels)
                            && Arrays.equals(this.micromapIndices, key.micromapIndices);
        }

        private static boolean rawFloatEquals(float[] first, float[] second) {
            if (first.length != second.length) {
                return false;
            }
            for (int index = 0; index < first.length; index++) {
                if (Float.floatToRawIntBits(first[index])
                        != Float.floatToRawIntBits(second[index])) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class Entry {
        private final Key key;
        private final PreparedBlas blas;
        private int references;
        private boolean buildRecorded;
        private boolean submitted;

        private Entry(Key key, PreparedBlas blas) {
            this.key = key;
            this.blas = blas;
        }
    }
}
