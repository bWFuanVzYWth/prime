package dev.primept.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.primept.PrimeClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
    private void primept$worldRaster(FrameGraphBuilder frame, GraphicsResourceAllocator allocator,
            FrameGraphBuilder.Inspector inspector) {
        // 26.2 compiles, uploads and updates section visibility after this call, outside the frame graph.
        if (!PrimeClient.skipWorldRaster()) frame.execute(allocator, inspector);
    }
}
