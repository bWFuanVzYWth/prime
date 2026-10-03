package dev.primept.mixin;

import dev.primept.capture.CustomSubmission;
import dev.primept.capture.ModelCapture;
import dev.primept.capture.NamedRawCapture;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CustomFeatureRenderer.Submit.class)
public abstract class CustomSubmissionMixin implements CustomSubmission {
    @Unique private NamedRawCapture.Emission primept$emission;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void primept$source(CallbackInfo callback) {
        var submit = (CustomFeatureRenderer.Submit)(Object)this;
        primept$emission = ModelCapture.tagCustom(submit.renderType(),
                                                  submit.customGeometryRenderer().getClass());
    }
    public NamedRawCapture.Emission primept$customEmission() {
        return primept$emission;
    }
}
