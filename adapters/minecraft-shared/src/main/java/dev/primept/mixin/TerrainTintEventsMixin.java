package dev.primept.mixin;

import dev.primept.capture.ExclusiveTerrainCapture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Biome packets use onChunkLoaded too; equal block palettes do not imply equal color. */
@Mixin(ClientLevel.class)
public abstract class TerrainTintEventsMixin {
    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void primept$tintColumn(ChunkPos pos, CallbackInfo callback) {
        if (ExclusiveTerrainCapture.ownsLevel(this))
            ExclusiveTerrainCapture.tintChanged(pos.x(), pos.z(), false);
    }
    @Inject(method = "clearTintCaches", at = @At("TAIL"))
    private void primept$tintAll(CallbackInfo callback) {
        if (ExclusiveTerrainCapture.ownsLevel(this))
            ExclusiveTerrainCapture.tintChanged(0, 0, true);
    }
}
