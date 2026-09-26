package dev.primept.mixin;

import dev.primept.capture.ModelCapture;
import dev.primept.capture.ModelSubmission;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelFeatureRenderer.Submit.class)
public abstract class ModelSubmissionMixin implements ModelSubmission {
    @Unique private ModelCapture.Submission primept$submission;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void primept$source(CallbackInfo callback) { primept$submission = ModelCapture.tagSubmission(); }
    public ModelCapture.Submission primept$submission() { return primept$submission; }
    public void primept$submission(ModelCapture.Submission source) { primept$submission = source; }
}
