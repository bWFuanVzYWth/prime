// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import dev.prime.render.terrain.ViewDistanceLimits;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Extends the vanilla task-priority table to cover Prime's player-ticket distance levels. */
@Mixin(ChunkTaskPriorityQueue.class)
public abstract class ChunkTaskPriorityQueueMixin {
    @Shadow
    @Final
    @Mutable
    public static int PRIORITY_LEVEL_COUNT;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void prime$extendPriorityLevelCount(CallbackInfo callback) {
        // PlayerTicketTracker uses maxDistance + 2 as its graph sentinel. The table size is one
        // larger again because the sentinel is a valid level value while tasks are being moved.
        // This field is also read by ChunkMap's queue-level supplier, so changing the shared
        // contract keeps construction, bounds checks and producer-side clamping consistent.
        PRIORITY_LEVEL_COUNT = Math.max(
                PRIORITY_LEVEL_COUNT,
                ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE + 3);
    }
}
