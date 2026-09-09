// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.prime.client.PrimeRuntime;
import dev.prime.render.scene.vanilla.DynamicSceneCapture;
import dev.prime.render.scene.vanilla.VanillaSceneBoundary;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Redirect(
            method = "invalidateCompiledGeometry",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"))
    private int prime$routeVanillaTerrainDistance(Options options) {
        return PrimeRuntime.instance().vanillaTerrainDistance(
                options.getEffectiveRenderDistance());
    }

    @Inject(method = "compileSections", at = @At("HEAD"), cancellable = true)
    private void prime$skipVanillaTerrainCompilation(
            net.minecraft.client.renderer.state.level.CameraRenderState camera,
            CallbackInfo callback) {
        if (!PrimeRuntime.instance().shouldMaintainVanillaTerrain()) {
            callback.cancel();
        }
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V"))
    private void prime$routeVanillaTerrainUpload(SectionRenderDispatcher dispatcher) {
        if (PrimeRuntime.instance().shouldMaintainVanillaTerrain()) {
            dispatcher.uploadTerrainBuffersToGpu();
        }
    }

    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void prime$beginDynamicCapture(
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            boolean renderOutline,
            CallbackInfo ci) {
        if (PrimeRuntime.instance().shouldCaptureDynamicScene()) {
            DynamicSceneCapture.begin(levelRenderState.cameraRenderState.pos);
        }
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V",
                    shift = At.Shift.AFTER))
    private void prime$finishDynamicCapture(CallbackInfo ci) {
        if (DynamicSceneCapture.active()) {
            PrimeRuntime.instance().captureDynamicScene(
                    DynamicSceneCapture.finish());
        }
    }

    @Inject(method = "submitEntities", at = @At("HEAD"))
    private void prime$beginEntityCapture(
            PoseStack poseStack,
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            CallbackInfo ci) {
        DynamicSceneCapture.beginElement(VanillaSceneBoundary.Element.ENTITY);
    }

    @Inject(method = "submitEntities", at = @At("RETURN"))
    private void prime$finishEntityCapture(
            PoseStack poseStack,
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            CallbackInfo ci) {
        DynamicSceneCapture.endElement(VanillaSceneBoundary.Element.ENTITY);
    }

    @Inject(method = "submitBlockEntities", at = @At("HEAD"))
    private void prime$beginBlockEntityCapture(
            PoseStack poseStack,
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            CallbackInfo ci) {
        DynamicSceneCapture.beginElement(
                VanillaSceneBoundary.Element.BLOCK_ENTITY);
    }

    @Inject(method = "submitBlockEntities", at = @At("RETURN"))
    private void prime$finishBlockEntityCapture(
            PoseStack poseStack,
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            CallbackInfo ci) {
        DynamicSceneCapture.endElement(
                VanillaSceneBoundary.Element.BLOCK_ENTITY);
    }

    @Inject(
            method = "submitFeatures",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void prime$beginParticleCapture(
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            boolean renderOutline,
            CallbackInfo ci) {
        DynamicSceneCapture.beginElement(VanillaSceneBoundary.Element.PARTICLE);
    }

    @Inject(
            method = "submitFeatures",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
                    shift = At.Shift.AFTER))
    private void prime$finishParticleCapture(
            LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector,
            boolean renderOutline,
            CallbackInfo ci) {
        DynamicSceneCapture.endElement(VanillaSceneBoundary.Element.PARTICLE);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
    private void prime$skipVanillaWorldRaster(
            FrameGraphBuilder frame,
            GraphicsResourceAllocator resourceAllocator,
            FrameGraphBuilder.Inspector inspector) {
        if (!PrimeRuntime.instance().shouldReplaceWorld()) {
            frame.execute(resourceAllocator, inspector);
        }
    }
}
