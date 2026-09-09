// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import dev.prime.render.terrain.ViewDistanceLimits;
import net.minecraft.server.level.ClientInformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Restores the unsigned view-distance byte used by Prime's integrated-server extension. */
@Mixin(ClientInformation.class)
public abstract class ClientInformationMixin {
    @ModifyArg(
            method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ClientInformation;<init>(Ljava/lang/String;ILnet/minecraft/world/entity/player/ChatVisiblity;ZILnet/minecraft/world/entity/HumanoidArm;ZZLnet/minecraft/server/level/ParticleStatus;)V"),
            index = 1)
    private static int prime$readUnsignedViewDistance(int signedDistance) {
        return ViewDistanceLimits.decodeRequestedDistance(signedDistance);
    }
}
