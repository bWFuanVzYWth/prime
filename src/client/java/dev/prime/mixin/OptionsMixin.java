package dev.prime.mixin;

import dev.prime.render.terrain.ViewDistanceLimits;
import dev.prime.config.PrimeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/** Extends only the client render-distance control; simulation distance keeps Minecraft's limit. */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Shadow
    protected Minecraft minecraft;

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/OptionInstance$IntRange;<init>(IIZ)V"),
            slice = @Slice(
                    from = @At(value = "CONSTANT", args = "stringValue=options.renderDistance"),
                    to = @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/Options;renderDistance:Lnet/minecraft/client/OptionInstance;",
                            opcode = Opcodes.PUTFIELD)),
            index = 1)
    private static int prime$maximumRenderDistance(int vanillaMaximum) {
        return Math.max(vanillaMaximum, ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE);
    }

    @ModifyArg(
            method = "buildPlayerInformation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ClientInformation;<init>(Ljava/lang/String;ILnet/minecraft/world/entity/player/ChatVisiblity;ZILnet/minecraft/world/entity/HumanoidArm;ZZLnet/minecraft/server/level/ParticleStatus;)V"),
            index = 1)
    private int prime$routeRequestedViewDistance(int configuredDistance) {
        return ViewDistanceLimits.requestedDistance(
                configuredDistance,
                PrimeConfig.rendererSettings().pathTracingEnabled(),
                this.minecraft.isLocalServer());
    }
}
