package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.primept.capture.CustomSubmission;
import dev.primept.capture.NamedRawCapture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CustomFeatureRenderer.class)
public abstract class CustomFeatureMixin {
    @WrapOperation(
            method = "buildGroup",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"))
    private void
    primept$emission(SubmitNodeCollector.CustomGeometryRenderer renderer, PoseStack.Pose pose,
                     VertexConsumer consumer, Operation<Void> original,
                     @Local CustomFeatureRenderer.Submit submit) {
        var emission = ((CustomSubmission)(Object)submit).primept$customEmission();
        var token = NamedRawCapture.enter(emission, consumer);
        boolean completed = false;
        try {
            original.call(renderer, pose, consumer);
            completed = true;
        } finally {
            NamedRawCapture.leave(token, completed);
        }
    }
}
