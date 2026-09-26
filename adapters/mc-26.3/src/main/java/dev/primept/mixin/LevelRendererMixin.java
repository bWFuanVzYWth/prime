package dev.primept.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.primept.PrimeClient;
import dev.primept.capture.DynamicCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @WrapMethod(method = "render")
    private void primept$dynamic(GraphicsResourceAllocator allocator, boolean outlines,
            net.minecraft.client.renderer.state.level.CameraRenderState camera,
            com.mojang.renderpearl.api.buffers.GpuBufferSlice fog, org.joml.Vector4f fogColor,
            boolean sky, boolean distant, Operation<Void> original) {
        DynamicCapture.begin(camera);
        try { original.call(allocator, outlines, camera, fog, fogColor, sky, distant); }
        finally { DynamicCapture.end(); }
    }
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
    private void primept$worldRaster(FrameGraphBuilder frame, GraphicsResourceAllocator allocator,
            FrameGraphBuilder.Inspector inspector) {
        // 26.2 compiles, uploads and updates section visibility after this call, outside the frame graph.
        if (!PrimeClient.skipWorldRaster()) frame.execute(allocator, inspector);
    }
}
