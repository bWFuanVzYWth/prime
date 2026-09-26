package dev.primept.capture;

import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.world.level.chunk.LevelChunk;

/** Read-only access to shared world source data, used once when restoring a retired raster renderer. */
public interface LoadedTerrainSnapshot {
    void primept$bindChunkStorage(AtomicReferenceArray<LevelChunk> chunks);
    void primept$restoreTerrainSnapshot(SectionOcclusionGraph graph);
}
