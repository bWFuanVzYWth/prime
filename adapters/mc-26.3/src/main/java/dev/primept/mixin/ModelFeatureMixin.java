package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.primept.capture.ModelCapture;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureMixin {
    @WrapMethod(method = "prepareModel")
    private <S> void primept$model(ModelFeatureRenderer.Submit<S> submit, Operation<Void> original) {
        var previous = ModelCapture.beginModel(submit);
        try { original.call(submit); }
        finally { ModelCapture.endModel(previous); }
    }
}
