package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.primept.capture.ModelCapture;
import dev.primept.PrimeClient;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ModelPart.class)
public abstract class ModelCubeMixin {
    @WrapOperation(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/model/geom/ModelPart$Cube;compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void
    primept$route(ModelPart.Cube cube, PoseStack.Pose pose, VertexConsumer consumer, int light,
                  int overlay, int color, Operation<Void> original) {
        boolean routed = PrimeClient.exclusiveFrameReady() && ModelCapture.routedModel();
        var observation = ModelCapture.beforeCube(cube, pose, consumer, color);
        if (routed) {
            // Empty geometry or excluded materials also stop here; unsupported source errors
            // poison this frame rather than replaying a downstream implementation.
            ModelCapture.skipCube(observation, true);
            return;
        }
        boolean completed = false;
        try {
            original.call(cube, pose, consumer, light, overlay, color);
            completed = true;
        } finally {
            ModelCapture.afterCube(observation, completed);
        }
    }
}
