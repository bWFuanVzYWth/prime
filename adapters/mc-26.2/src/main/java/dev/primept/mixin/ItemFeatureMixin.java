package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.primept.PrimeClient;
import dev.primept.capture.ItemCapture;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureMixin {
    @WrapMethod(method = "prepareMainSubmit")
    private void primept$submit(ItemFeatureRenderer.Submit submit, Operation<Void> original) {
        var previous = ItemCapture.enter(submit, PrimeClient.exclusiveFrameReady());
        boolean completed = false;
        try {
            original.call(submit);
            completed = true;
        } finally {
            ItemCapture.leave(previous, completed);
        }
    }
    @WrapOperation(
            method = "prepareMainSubmit",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"))
    private void
    primept$quad(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad, QuadInstance colors,
                 Operation<Void> original) {
        if (!ItemCapture.quad(consumer, pose, quad, colors))
            original.call(consumer, pose, quad, colors);
    }
}
