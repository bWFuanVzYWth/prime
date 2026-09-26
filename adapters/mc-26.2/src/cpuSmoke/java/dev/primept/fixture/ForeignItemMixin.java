package dev.primept.fixture;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ItemFeatureRenderer.class)
public abstract class ForeignItemMixin {
    @Inject(method = "prepareMainSubmit", at = @At("HEAD"))
    private void foreign$item(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
        ++dev.primept.capture.ItemCpuSmoke.foreignCalls;
    }
}
