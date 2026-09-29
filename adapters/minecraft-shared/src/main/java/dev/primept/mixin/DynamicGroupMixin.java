package dev.primept.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.primept.capture.DynamicCapture;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public abstract class DynamicGroupMixin {
    @Inject(method = "getOrAddDraw", at = @At("RETURN"))
    private void primept$material(RenderType type,
                                  CallbackInfoReturnable<StagedVertexBuffer.Draw> callback,
                                  @Local PreparedRenderType prepared) {
        DynamicCapture.register(callback.getReturnValue(), type, prepared);
    }
}
