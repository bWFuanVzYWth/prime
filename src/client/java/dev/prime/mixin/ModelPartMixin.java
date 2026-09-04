package dev.prime.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.prime.render.scene.vanilla.PrimeModelPart;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ModelPart.class)
public abstract class ModelPartMixin implements PrimeModelPart {
    @Shadow @Final private Map<String, ModelPart> children;

    @Shadow
    private void compile(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int lightCoords,
            int overlayCoords,
            int color) {
        throw new AssertionError();
    }

    @Override
    public Map<String, ModelPart> prime$children() {
        return this.children;
    }

    @Override
    public void prime$compile(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int lightCoords,
            int overlayCoords,
            int color) {
        this.compile(pose, consumer, lightCoords, overlayCoords, color);
    }
}
