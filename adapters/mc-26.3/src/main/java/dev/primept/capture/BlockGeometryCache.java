package dev.primept.capture;

import java.util.LinkedHashMap;
import dev.primept.PrimeClient;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.EncodingFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4fc;

/** Resource-scoped FRAPI geometry, before wrapper transform and external tint. Render thread only. */
public final class BlockGeometryCache {
    private static final boolean ENABLED =
            Boolean.getBoolean("primept.enabled") &&
            Boolean.parseBoolean(System.getProperty("primept.geometryCache", "false"));
    private static final ResourceContext RESOURCES = new ResourceContext();
    private BlockGeometryCache() {}
    public record Stats(long hits, long misses, long nullKeys, long emits, long emittedBytes,
                        long avoidedBytes, long retainedBytes, int entries, long resets) {}
    public static boolean enabled() {
        return ENABLED;
    }
    public static Stats stats() {
        return RESOURCES.stats();
    }
    public static void resourceReload() {
        if (ENABLED && PrimeClient.captureResourcesEnabled())
            RESOURCES.resourceReload();
    }
    /**
     * Releases only PT's lookup ownership. Host render states may still consume the immutable source
     * result they already borrowed; their lifetime is independent of a PT backend's private cache.
     */
    public static void releaseToVanilla() {
        RESOURCES.resourceReload();
        RESOURCES.environmentKnown = false;
    }
    static void environment(boolean leaves) {
        RESOURCES.environment(leaves);
    }
    public static boolean resolve(BlockStateModel model, BlockModelRenderState output,
                                  BlockState state, long seed, Matrix4fc transformation) {
        return RESOURCES.resolve(model, output, state, seed, transformation);
    }
    /** Survives world changes; resource/graphics invalidation clears owned meshes, not in-flight submit references. */
    private static final class ResourceContext {
        private static final int MAX_ENTRIES = 4096;
        private static final long MAX_BYTES = 32L << 20;
        private final LinkedHashMap<Object, Entry> entries = new LinkedHashMap<>(64, .75f, true);
        private boolean environmentKnown, cutoutLeaves;
        private long hits, misses, nullKeys, emits, emittedBytes, avoidedBytes, retainedBytes,
                resets;

        private record Entry(Mesh mesh, long bytes) {}
        Stats stats() {
            return new Stats(hits, misses, nullKeys, emits, emittedBytes, avoidedBytes,
                             retainedBytes, entries.size(), resets);
        }
        void resourceReload() {
            entries.clear();
            retainedBytes = 0;
            ++resets;
        }
        void environment(boolean currentCutoutLeaves) {
            if (environmentKnown && cutoutLeaves != currentCutoutLeaves)
                resourceReload();
            cutoutLeaves = currentCutoutLeaves;
            environmentKnown = true;
        }

        /** A null key explicitly selects the original Fabric path; no blockstate or model-identity fallback key. */
        boolean resolve(BlockStateModel model, BlockModelRenderState output, BlockState state,
                        long seed, Matrix4fc transformation) {
            if (!ENABLED || !PrimeClient.captureResourcesEnabled() ||
                !BlockGeometryCapabilities.supported())
                return false;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null)
                environment(minecraft.options.cutoutLeaves().get());
            Object key = model.createGeometryKey(BlockAndTintGetter.EMPTY, BlockPos.ZERO, state,
                                                 output.scratchRandomSource(seed));
            if (key == null) {
                ++nullKeys;
                return false;
            }
            // Preserve Fabric's actual material-flag query before emission; keys promise geometry, not flags.
            boolean translucent = model.hasMaterialFlag(BlockAndTintGetter.EMPTY, BlockPos.ZERO,
                                                        state, output.scratchRandomSource(seed),
                                                        BakedQuad.FLAG_TRANSLUCENT);
            Entry previous = entries.get(key);
            if (previous != null) {
                ++hits;
                avoidedBytes += previous.bytes;
                ((CachedBlockGeometry)output)
                        .primept$geometry(previous.mesh, transformation, translucent);
                return true;
            }
            ++misses;
            var mutable = Renderer.get().mutableMesh();
            // Key evaluation can consume random (e.g. WeightedVariants). Actual emission starts at the same seed.
            model.emitQuads(mutable.emitter(), BlockAndTintGetter.EMPTY, BlockPos.ZERO, state,
                            output.scratchRandomSource(seed), ignored -> false);
            ++emits;
            Mesh mesh = mutable.immutableCopy();
            long bytes = (long)mesh.size() * EncodingFormat.TOTAL_STRIDE * Integer.BYTES;
            emittedBytes += bytes;
            if (bytes <= MAX_BYTES) {
                while (!entries.isEmpty() &&
                       (entries.size() >= MAX_ENTRIES || retainedBytes + bytes > MAX_BYTES))
                    retainedBytes -= entries.pollFirstEntry().getValue().bytes;
                entries.put(key, new Entry(mesh, bytes));
                retainedBytes += bytes;
            }
            ((CachedBlockGeometry)output).primept$geometry(mesh, transformation, translucent);
            return true;
        }
    }
}
