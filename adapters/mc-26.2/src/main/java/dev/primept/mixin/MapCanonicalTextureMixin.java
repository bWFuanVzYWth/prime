package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.primept.capture.CanonicalTextureSources;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.client.resources.MapTextureManager$MapInstance")
public abstract class MapCanonicalTextureMixin {
    @Shadow private DynamicTexture texture;
    @WrapMethod(method = "updateTextureIfNeeded")
    private void primept$actualMapUpdate(Operation<Void> original) {
        DynamicTexture previous = CanonicalTextureSources.beginMapRead(texture);
        try {
            original.call();
        } finally {
            CanonicalTextureSources.endMapRead(previous);
        }
        CanonicalTextureSources.register(texture, CanonicalTextureSources.Kind.MAP);
    }
}
