// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import dev.prime.render.terrain.ViewDistanceLimits;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Lets the integrated server actually supply the chunks selected by Prime's client setting. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @ModifyArg(
            method = "setServerViewDistance",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Mth;clamp(III)I"),
            index = 2)
    private static int prime$maximumIntegratedViewDistance(int vanillaMaximum) {
        return ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE;
    }
}
