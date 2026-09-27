package dev.primept.mixin;

import dev.primept.capture.ExclusiveTerrainCapture;
import net.minecraft.client.multiplayer.ClientChunkCache;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class ExclusiveChunkEventsMixin {
    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void primept$loaded(CallbackInfoReturnable<LevelChunk> callback) {
        if (!ExclusiveTerrainCapture.ownsLevel(((ClientChunkCache)(Object)this).getLevel()))
            return;
        if (callback.getReturnValue() != null) {
            var pos = callback.getReturnValue().getPos();
            ExclusiveTerrainCapture.chunkLoaded(pos.x(), pos.z());
        }
    }
    @Inject(method = "drop", at = @At("TAIL"))
    private void primept$unloaded(ChunkPos pos, CallbackInfo callback) {
        if (ExclusiveTerrainCapture.ownsLevel(((ClientChunkCache)(Object)this).getLevel()))
            ExclusiveTerrainCapture.chunkUnloaded(pos.x(), pos.z());
    }
    @Inject(method = {"updateViewCenter", "updateViewRadius"}, at = @At("TAIL"))
    private void primept$cacheWindow(CallbackInfo callback) {
        if (ExclusiveTerrainCapture.ownsLevel(((ClientChunkCache)(Object)this).getLevel()))
            ExclusiveTerrainCapture.cacheWindowChanged();
    }
}
