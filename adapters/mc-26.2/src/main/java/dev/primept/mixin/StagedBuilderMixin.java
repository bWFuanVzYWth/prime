package dev.primept.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.primept.capture.DynamicCapture;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StagedVertexBuffer.class)
public abstract class StagedBuilderMixin {
    @Inject(method = "getVertexBuilder", at = @At("RETURN"))
    private void primept$builder(StagedVertexBuffer.Draw draw,
                                 CallbackInfoReturnable<VertexConsumer> callback) {
        DynamicCapture.builder(draw, callback.getReturnValue());
    }
}
