package dev.primept.mixin;

import dev.primept.capture.LoadedTerrainSnapshot;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public abstract class ChunkStorageSnapshotMixin {
    @Shadow @Final private AtomicReferenceArray<LevelChunk> chunks;
    @Shadow private int viewCenterX;
    @Shadow private int viewCenterZ;
    @Shadow @Final private int chunkRadius;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void primept$bindStorage(ClientChunkCache owner, int radius, CallbackInfo callback) {
        // Radius changes construct a replacement storage. Retain its array, never a copied chunk list.
        ((LoadedTerrainSnapshot)owner).primept$bindChunkStorage(chunks);
        ((LoadedTerrainSnapshot)owner)
                .primept$bindSourceWindow(()
                                                  -> dev.primept.capture.ColumnWindow.centered(
                                                          viewCenterX, viewCenterZ, chunkRadius));
    }
}
