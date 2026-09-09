// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import dev.prime.render.scene.vanilla.DynamicSceneCapture;
import dev.prime.render.scene.vanilla.DynamicSceneFrame;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SubmitRenderPhase.class, remap = false)
public abstract class SubmitRenderPhaseMixin {
    @Inject(method = "submit", at = @At("HEAD"), remap = false)
    private void prime$reportOpaqueCustomSubmit(
            SubmitNodeCollection collection,
            SubmitNode node,
            CallbackInfo ci) {
        DynamicSceneCapture.reportCompatibilityIssue(
                DynamicSceneFrame.CompatibilityIssue.CUSTOM_SUBMIT_NODE);
    }
}
