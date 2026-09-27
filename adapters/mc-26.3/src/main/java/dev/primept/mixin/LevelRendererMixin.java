package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.primept.PrimeClient;
import dev.primept.capture.DynamicCapture;
import dev.primept.capture.ExclusiveTerrainCapture;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow @Final private SubmitNodeStorage submitNodeStorage;
    @Shadow @Final private FeatureRenderDispatcher featureRenderDispatcher;
    @Shadow private boolean currentFrameRendersEntityOutline;

    @WrapMethod(method = "render")
    private void primept$dynamic(GraphicsResourceAllocator allocator, boolean outlines,
                                 net.minecraft.client.renderer.state.level.CameraRenderState camera,
                                 com.mojang.renderpearl.api.buffers.GpuBufferSlice fog,
                                 org.joml.Vector4f fogColor, boolean sky, boolean distant,
                                 Operation<Void> original) {
        if (!PrimeClient.ownsWorldRendering()) {
            original.call(allocator, outlines, camera, fog, fogColor, sky, distant);
            return;
        }
        if (PrimeClient.offlineActive())
            return;
        if (!ExclusiveTerrainCapture.active())
            return; // Retirement failed: never call an absent vanilla owner.
        ExclusiveTerrainCapture.prepareFrame(camera);
        if (ExclusiveTerrainCapture.failure() != null)
            return;
        DynamicCapture.begin(camera);
        ExclusiveTerrainCapture.beginSourceFrame();
        try {
            // Keep render HEAD hooks (Fabric context preparation) and the actual submitFeatures callbacks.
            // The injection below exits immediately after the corresponding real feature preparation.
            original.call(allocator, outlines, camera, fog, fogColor, sky, distant);
        } finally {
            RenderSystem.isRenderingLevel = false;
            ExclusiveTerrainCapture.endSourceFrame();
            DynamicCapture.end();
        }
    }
    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
    private void primept$noRasterViewArea(CallbackInfo callback) {
        if (PrimeClient.ownsWorldRendering())
            callback.cancel();
    }
    @Inject(method = "render",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;")
            ,
            cancellable = true)
    private void
    primept$finishSourceFrame(CallbackInfo callback) {
        if (!ExclusiveTerrainCapture.sourceFrame())
            return;
        FeatureRenderDispatcher.PreparedFrame prepared = null;
        try {
            // finishPrepare and finishExecute/endDraw are actual callbacks, each executed exactly once.
            prepared = featureRenderDispatcher.prepareFrame(submitNodeStorage);
            currentFrameRendersEntityOutline = false;
        } finally {
            net.minecraft.util.profiling.Profiler.get().pop();
            RenderSystem.getModelViewStack().popMatrix();
            // Vanilla restores the caller's model-view stack before feature finishExecute/endDraw.
            if (prepared != null)
                prepared.close();
        }
        Runnable ready = levelRenderState.playerCompiledSectionCallback;
        if (ready != null && ExclusiveTerrainCapture.sourceSectionReady(SectionPos.asLong(
                                     levelRenderState.cameraRenderState.blockPos)))
            ready.run();
        callback.cancel();
    }
    @Inject(method = "invalidateCompiledGeometry", at = @At("HEAD"), cancellable = true)
    private void primept$exclusiveCompiler(CallbackInfo callback) {
        if (ExclusiveTerrainCapture.vanillaSuspended()) {
            ExclusiveTerrainCapture.invalidateAll();
            callback.cancel();
        }
    }
    @Inject(method = "invalidateCompiledGeometry", at = @At("TAIL"))
    private void primept$restoreLoadedWorld(net.minecraft.client.multiplayer.ClientLevel level,
                                            net.minecraft.client.Options options,
                                            net.minecraft.client.Camera camera,
                                            net.minecraft.client.color.block.BlockColors colors,
                                            CallbackInfo callback) {
        ExclusiveTerrainCapture.restoreVanillaSnapshot(
                level, ((LevelRenderer)(Object)this).sectionOcclusionGraph());
    }
    @Inject(method = "expectedChunks", at = @At("HEAD"), cancellable = true)
    private void primept$expected(CallbackInfoReturnable<LongCollection> callback) {
        if (ExclusiveTerrainCapture.vanillaSuspended())
            callback.setReturnValue(ExclusiveTerrainCapture.expectedChunks());
    }
}
