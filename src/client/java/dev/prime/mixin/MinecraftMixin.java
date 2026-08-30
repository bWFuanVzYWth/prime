package dev.prime.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.prime.PrimeClient;
import dev.prime.client.PrimeRuntime;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.streamline.StreamlineReflex;
import dev.prime.streamline.StreamlineFrameGeneration;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "handleGlobalKeyPress", at = @At("HEAD"), cancellable = true)
    private void prime$screenshotModeShortcut(
            InputConstants.Key key,
            boolean controlDown,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (PrimeRuntime.instance().handleScreenshotShortcut(
                minecraft, key, controlDown)) {
            callbackInfo.setReturnValue(true);
        }
    }

    @Inject(method = "close()V", at = @At("HEAD"))
    private void prime$shutdown(CallbackInfo ci) {
        RuntimeException failure = null;
        failure = ResourceCleanup.run(StreamlineFrameGeneration::shutdown, failure);
        failure = ResourceCleanup.run(StreamlineReflex::shutdown, failure);
        failure = ResourceCleanup.run(PrimeClient::shutdownStreamline, failure);
        failure = ResourceCleanup.run(PrimeRuntime.instance()::shutdown, failure);
        if (failure != null) {
            PrimeInfo.LOGGER.warn(
                    "Prime shutdown was incomplete; Minecraft teardown will continue",
                    failure);
        }
    }
}
