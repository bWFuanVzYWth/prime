// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies player-ticket transitions with the unsigned distance stored by Minecraft's graph. */
@Mixin(targets = "net.minecraft.server.level.DistanceManager$PlayerTicketTracker")
public abstract class PlayerTicketTrackerMixin {
    @Shadow
    private int viewDistance;

    @Shadow
    private void onLevelChange(long key, int level, boolean saw, boolean sees) {
        throw new AssertionError();
    }

    @Inject(method = "updateViewDistance", at = @At("HEAD"), cancellable = true)
    private void prime$updateUnsignedViewDistance(int newViewDistance, CallbackInfo callback) {
        Long2ByteMap levels = ((FixedPlayerDistanceChunkTrackerAccessor) this).prime$distanceLevels();
        for (Long2ByteMap.Entry entry : levels.long2ByteEntrySet()) {
            int level = Byte.toUnsignedInt(entry.getByteValue());
            this.onLevelChange(
                    entry.getLongKey(),
                    level,
                    level <= this.viewDistance,
                    level <= newViewDistance);
        }
        this.viewDistance = newViewDistance;
        callback.cancel();
    }
}
