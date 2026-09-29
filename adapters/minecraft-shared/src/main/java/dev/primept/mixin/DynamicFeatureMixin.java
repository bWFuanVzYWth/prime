package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.primept.capture.DynamicCapture;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.LeashFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RenderTypeFeatureRenderer.class)
public abstract class DynamicFeatureMixin {
    @WrapMethod(method = "prepareGroup")
    private void primept$sourceGroup(FeatureFrameContext context,
                                     List<? extends SubmitNode> submits, boolean reorder,
                                     Operation<Void> original) {
        // These two renderers bake directional/AO shading on the CPU before Draw.append.
        Object renderer = this;
        boolean previous =
                DynamicCapture.sourceGroup(!(renderer instanceof MovingBlockFeatureRenderer) &&
                                           !(renderer instanceof LeashFeatureRenderer));
        try {
            original.call(context, submits, reorder);
        } finally {
            DynamicCapture.sourceGroup(previous);
        }
    }
}
