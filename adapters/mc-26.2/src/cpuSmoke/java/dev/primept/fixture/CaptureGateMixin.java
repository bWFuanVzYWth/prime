package dev.primept.fixture;

import dev.primept.PrimeClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CPU test mod only: source hooks can run without constructing a host/device renderer slot. */
@Mixin(PrimeClient.class)
public abstract class CaptureGateMixin {
    @Inject(method = "captureEnabled", at = @At("HEAD"), cancellable = true)
    private static void test$sourceWithoutDevice(CallbackInfoReturnable<Boolean> result) {
        if (!Boolean.getBoolean("primept.smoke.realCaptureGates"))
            result.setReturnValue(true);
    }
    @Inject(method = "exclusiveFrameReady", at = @At("HEAD"), cancellable = true)
    private static void test$itemExclusive(CallbackInfoReturnable<Boolean> result) {
        if (dev.primept.capture.ItemCpuSmoke.exclusive)
            result.setReturnValue(true);
    }
}
