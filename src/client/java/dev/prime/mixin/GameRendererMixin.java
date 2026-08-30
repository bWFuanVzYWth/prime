package dev.prime.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.prime.config.PrimeConfig;
import dev.prime.client.PrimeRuntime;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private RenderTarget mainRenderTarget;

    @Shadow
    public abstract GameRenderState gameRenderState();

    @WrapMethod(method = "renderLevel")
    private void prime$retireAfterHostSubmissionFailure(
            DeltaTracker deltaTracker, Operation<Void> original) {
        try {
            original.call(deltaTracker);
        } catch (RuntimeException exception) {
            PrimeRuntime.instance().minecraftHostSubmissionFailed(exception);
            throw exception;
        }
    }

    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void prime$beginFrame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        PrimeRuntime.instance().beginFrame(minecraft, PrimeConfig.rendererSettings());
    }

    @ModifyArg(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
                    ordinal = 0),
            index = 0)
    private Matrix4f prime$captureCamera(Matrix4f projection) {
        var camera = this.gameRenderState().levelRenderState.cameraRenderState;
        PrimeRuntime.instance().captureCamera(
                projection,
                camera.projectionMatrix,
                camera.viewRotationMatrix,
                camera.pos.x,
                camera.pos.y,
                camera.pos.z,
                this.gameRenderState().levelRenderState.skyRenderState.sunAngle);
        return projection;
    }

    @Inject(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void prime$clearUiAlpha(
            DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        PrimeRuntime.instance().clearUiAlpha(this.mainRenderTarget);
    }

    @Inject(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V",
                    shift = At.Shift.AFTER))
    private void prime$captureUiAlpha(
            DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        PrimeRuntime.instance().captureUiAlpha(this.mainRenderTarget);
    }

    @Inject(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
                    shift = At.Shift.AFTER))
    private void prime$renderRayTracedWorld(DeltaTracker deltaTracker, CallbackInfo ci) {
        PrimeRuntime.instance().renderWorld(this.mainRenderTarget);
    }
}
