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

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void primept$profileStart(CallbackInfo callback) {
        PrimeClient.beginWorldRender();
    }

    @Inject(method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
                    shift = At.Shift.AFTER))
    private void
    primept$render(CallbackInfo callback) {
        var renderer = (GameRenderer)(Object)this;
        PrimeClient.render(renderer.gameRenderState().levelRenderState.cameraRenderState,
                           renderer.mainRenderTarget());
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
