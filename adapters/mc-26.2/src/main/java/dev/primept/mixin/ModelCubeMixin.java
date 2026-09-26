package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.primept.capture.ModelCapture;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ModelPart.Cube.class)
public abstract class ModelCubeMixin {
    @WrapMethod(method = "compile")
    private void primept$instance(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay,
            int color, Operation<Void> original) {
        var observation = ModelCapture.beforeCube((ModelPart.Cube) (Object) this, pose, consumer, color);
        // Never replay or suppress the actual leaf callback, including foreign injections.
        boolean completed = false;
        try {
            original.call(pose, consumer, light, overlay, color);
            completed = true;
        } finally { ModelCapture.afterCube(observation, completed); }
    }
}
