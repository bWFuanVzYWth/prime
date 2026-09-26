package dev.primept.mixin;

import dev.primept.capture.DynamicTextures;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DynamicTexture.class)
public abstract class DynamicTextureMixin {
    @Inject(method = "upload", at = @At("RETURN"))
    private void primept$sourcePixels(CallbackInfo callback) {
        var source = (DynamicTexture) (Object) this;
        DynamicTextures.image(source.getTexture(), source.getPixels());
    }
}
