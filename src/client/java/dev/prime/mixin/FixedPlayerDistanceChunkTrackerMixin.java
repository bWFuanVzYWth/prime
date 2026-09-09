// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Preserves player-ticket distances above 127.
 *
 * <p>Vanilla bounds this tracker to 32 and stores its levels in a byte. Prime extends the bound to
 * 251, so the byte must be interpreted as the unsigned value that the graph wrote. All required
 * levels, including the graph's 253 sentinel, still fit exactly in an unsigned byte.
 */
@Mixin(targets = "net.minecraft.server.level.DistanceManager$FixedPlayerDistanceChunkTracker")
public abstract class FixedPlayerDistanceChunkTrackerMixin {
    @Inject(method = "getLevel", at = @At("RETURN"), cancellable = true)
    private void prime$readUnsignedDistance(long node, CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(Byte.toUnsignedInt(callback.getReturnValue().byteValue()));
    }
}
