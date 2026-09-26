package dev.primept.mixin;

import dev.primept.capture.CaptureStagedBuffer;
import dev.primept.capture.ExclusiveTerrainCapture;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FeatureRenderDispatcher.class)
public abstract class ExclusiveFeatureMixin {
    @Redirect(method = "prepareFrameWithContext",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;upload()V"))
    private void
    primept$sourcePages(StagedVertexBuffer staged) {
        if (ExclusiveTerrainCapture.sourceFrame())
            ((CaptureStagedBuffer)staged).primept$drainCapturedSource();
        else
            staged.upload();
    }
}
