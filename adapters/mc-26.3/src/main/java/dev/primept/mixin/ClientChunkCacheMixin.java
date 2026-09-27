package dev.primept.mixin;

import dev.primept.PrimeClient;
import dev.primept.capture.LoadedTerrainSnapshot;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin implements LoadedTerrainSnapshot {
    @Unique private AtomicReferenceArray<LevelChunk> primept$chunkStorage;
    @Unique private java.util.function.Supplier<dev.primept.capture.ColumnWindow> primept$window;
    @Override
    public void
    primept$bindSourceWindow(java.util.function.Supplier<dev.primept.capture.ColumnWindow> window) {
        primept$window = window;
    }
    @Override
    public dev.primept.capture.ColumnWindow primept$sourceWindow() {
        return primept$window == null ? null : primept$window.get();
    }

    @Override
    public void primept$bindChunkStorage(AtomicReferenceArray<LevelChunk> chunks) {
        primept$chunkStorage = chunks;
    }
    @Override
    public void primept$restoreTerrainSnapshot(SectionOcclusionGraph graph) {
        var cache = (ClientChunkCache)(Object)this;
        var loaded = new LongOpenHashSet();
        var empty = new LongOpenHashSet();
        var none = new LongOpenHashSet();
        for (int index = 0; index < primept$chunkStorage.length(); ++index) {
            var chunk = primept$chunkStorage.get(index);
            if (chunk == null)
                continue;
            var pos = chunk.getPos();
            // A moved storage may retain an out-of-range slot until replacement; match real getChunk visibility.
            if (cache.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != chunk)
                continue;
            loaded.add(ChunkPos.pack(pos.x(), pos.z()));
            var sections = chunk.getSections();
            for (int y = 0; y < sections.length; ++y)
                if (sections[y].hasOnlyAir())
                    empty.add(SectionPos.asLong(pos.x(), y + chunk.getMinSectionY(), pos.z()));
        }
        graph.updateLoadedChunks(loaded, none);
        graph.updateEmptySections(empty, none);
        PrimeClient.LOGGER.info(
                "Restored vanilla terrain source snapshot: {} loaded columns, {} empty sections",
                loaded.size(), empty.size());
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void primept$drop(ChunkPos position, CallbackInfo callback) {
        PrimeClient.CAPTURE.dropChunk(position.x(), position.z());
    }
}
