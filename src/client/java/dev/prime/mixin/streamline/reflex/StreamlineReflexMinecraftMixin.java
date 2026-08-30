package dev.prime.mixin.streamline.reflex;

import dev.prime.PrimeClient;
import dev.prime.streamline.StreamlineReflex;
import dev.prime.streamline.StreamlineFrameGeneration;
import net.minecraft.client.FramerateLimiter;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class StreamlineReflexMinecraftMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void prime$reflexBeginFrame(boolean advanceGameTime, CallbackInfo ci) {
        StreamlineReflex.beginFrame();
        StreamlineFrameGeneration.beginFrame(StreamlineReflex.currentFrameIndex());
    }

    @Inject(
            method = "renderFrame(Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V",
                    ordinal = 0))
    private void prime$reflexEndSimulation(boolean advanceGameTime, CallbackInfo ci) {
        StreamlineReflex.endSimulation();
        StreamlineReflex.beginRenderSubmission();
    }

    @Redirect(
            method = "renderFrame(Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V",
                    ordinal = 0))
    private void prime$reflexLimitDisplayFps(int framerateLimit) {
        if (!StreamlineReflex.shouldSkipVanillaFrameLimiter()) {
            FramerateLimiter.limitDisplayFPS(framerateLimit);
        }
    }

    @Inject(method = "onGameLoadFinished", at = @At("RETURN"))
    private void prime$reflexGameLoadFinished(GameLoadCookie cookie, CallbackInfo ci) {
        StreamlineReflex.initialize(PrimeClient.streamline());
        StreamlineFrameGeneration.initialize(PrimeClient.streamline());
        StreamlineReflex.onGameLoadFinished();
    }
}
