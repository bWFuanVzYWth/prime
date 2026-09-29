package dev.primept.mixin;

import dev.primept.capture.ExclusiveTerrainCapture;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wake blocked host sources after the actual readiness flag changes; never mark geometry dirty. */
@Mixin(LevelLightEngine.class)
public abstract class TerrainLightReadinessMixin {
    @Inject(method = "setLightEnabled", at = @At("RETURN"))
    private void primept$sourceReadiness(ChunkPos pos, boolean enabled, CallbackInfo callback) {
        ExclusiveTerrainCapture.lightStatusChanged((LevelLightEngine)(Object)this, pos.x(),
                                                   pos.z());
    }
}
