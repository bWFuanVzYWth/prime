package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.NativeImage;
import dev.primept.capture.CanonicalTextureSources;
import java.util.function.Supplier;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TextureManager.class)
public abstract class MissingCanonicalTextureMixin {
    @WrapOperation(method = "<init>",
                   at = @At(value = "NEW",
                            target = "net/minecraft/client/renderer/texture/DynamicTexture"))
    private DynamicTexture
    primept$source(Supplier<String> label, NativeImage image, Operation<DynamicTexture> original) {
        DynamicTexture result = original.call(label, image);
        CanonicalTextureSources.register(result, CanonicalTextureSources.Kind.MISSING);
        return result;
    }
}
