package dev.primept.mixin;

import dev.primept.PrimeClient;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Inject(method = "drop", at = @At("HEAD"))
    private void primept$drop(ChunkPos position, CallbackInfo callback) {
        PrimeClient.CAPTURE.dropChunk(position.x(), position.z());
    }
}
