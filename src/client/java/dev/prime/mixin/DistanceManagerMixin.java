// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import dev.prime.render.terrain.ViewDistanceLimits;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Keeps Minecraft's chunk-loading tickets in step with Prime's extended view distance. */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/DistanceManager$PlayerTicketTracker;<init>(Lnet/minecraft/server/level/DistanceManager;I)V"),
            index = 1)
    private int prime$maximumPlayerTicketDistance(int vanillaMaximum) {
        return ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE;
    }
}
