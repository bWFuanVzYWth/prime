package dev.primept.mixin;

import com.mojang.blaze3d.textures.GpuTexture;
import dev.primept.capture.DynamicTextures;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractTexture.class)
public abstract class SourceTextureLifetimeMixin {
    @Shadow protected GpuTexture texture;
    @Inject(method = "releaseTextures", at = @At("HEAD"))
    private void primept$release(CallbackInfo callback) {
        DynamicTextures.release(texture);
    }
}
