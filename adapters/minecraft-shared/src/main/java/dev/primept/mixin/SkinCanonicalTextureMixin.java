package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.NativeImage;
import dev.primept.capture.CanonicalTextureSources;
import java.util.function.Supplier;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SkinTextureDownloader.class)
public abstract class SkinCanonicalTextureMixin {
    @WrapOperation(method = "lambda$registerTextureInManager$0",
                   at = @At(value = "NEW",
                            target = "net/minecraft/client/renderer/texture/DynamicTexture"))
    private DynamicTexture
    primept$source(Supplier<String> label, NativeImage image, Operation<DynamicTexture> original) {
        DynamicTexture result = original.call(label, image);
        CanonicalTextureSources.register(result, CanonicalTextureSources.Kind.SKIN);
        return result;
    }
}
