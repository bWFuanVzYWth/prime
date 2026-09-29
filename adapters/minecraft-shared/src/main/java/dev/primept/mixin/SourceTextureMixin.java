package dev.primept.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.primept.capture.DynamicTextures;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ReloadableTexture.class)
public abstract class SourceTextureMixin {
    @Inject(method = "doLoad", at = @At("RETURN"))
    private void primept$sourcePixels(NativeImage image, CallbackInfo callback) {
        DynamicTextures.image(((ReloadableTexture)(Object)this).getTexture(), image);
    }
}
