package dev.primept.mixin;

import dev.primept.PrimeClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void primept$selectRenderer(CallbackInfo callback) {
        PrimeClient.beginFrame();
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void primept$extractionDone(CallbackInfo callback) {
        PrimeClient.endExtraction();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void primept$profileStart(CallbackInfo callback) {
        PrimeClient.beginWorldRender();
    }

    @Inject(method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
                    shift = At.Shift.AFTER))
    private void
    primept$render(CallbackInfo callback) {
        var renderer = (GameRenderer)(Object)this;
        PrimeClient.render(renderer.gameRenderState().levelRenderState.cameraRenderState,
                           renderer.mainRenderTarget());
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void primept$frozenHand(CallbackInfo callback) {
        if (PrimeClient.offlineActive())
            callback.cancel();
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void primept$reset(ClientLevel level, CallbackInfo callback) {
        PrimeClient.resetWorld();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void primept$close(CallbackInfo callback) {
        PrimeClient.close();
    }
}
