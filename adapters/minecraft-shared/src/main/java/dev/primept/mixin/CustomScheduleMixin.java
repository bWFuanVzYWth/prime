package dev.primept.mixin;

import dev.primept.capture.NamedRawCapture;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FeatureRenderDispatcher.class)
public abstract class CustomScheduleMixin {
    @Shadow private FeatureRenderDispatcher.PreparedFrame preparedFrame;
    @Inject(method = "prepareFrameWithContext",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/renderer/feature/FeatureRenderer;beginPrepare(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;)V",
                    ordinal = 0))
    private void
    primept$freeze(CallbackInfoReturnable<FeatureRenderDispatcher.PreparedFrame> callback) {
        NamedRawCapture.freeze(((PreparedFrameAccessor)preparedFrame).primept$submits());
    }
}
