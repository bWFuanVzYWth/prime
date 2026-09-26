package dev.primept.mixin;

import dev.primept.PrimeClient;
import dev.primept.capture.DynamicTextures;
import dev.primept.capture.BlockGeometryCache;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {
    @Inject(method = "upload", at = @At("HEAD"))
    private void primept$invalidateGeometry(SpriteLoader.Preparations preparations,
                                            CallbackInfo callback) {
        BlockGeometryCache.resourceReload();
    }
    @Inject(method = "upload", at = @At("RETURN"))
    private void primept$captureAtlas(SpriteLoader.Preparations preparations,
                                      CallbackInfo callback) {
        if (((TextureAtlas)(Object)this).location().equals(TextureAtlas.LOCATION_BLOCKS))
            PrimeClient.CAPTURE.captureAtlas(preparations);
        DynamicTextures.atlas((TextureAtlas)(Object)this, preparations);
    }
}
