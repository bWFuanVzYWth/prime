package dev.primept.mixin;

import dev.primept.StreamlineFrames;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class StreamlineFrameMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void primept$beginFrame(boolean advanceGameTime, CallbackInfo ci) {
        StreamlineFrames.begin();
    }
    @Inject(method = "renderFrame(Z)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V", ordinal = 0))
    private void
    primept$renderBegin(boolean advanceGameTime, CallbackInfo ci) {
        StreamlineFrames.renderBegin();
    }
}
