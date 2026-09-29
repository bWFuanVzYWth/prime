package dev.primept.mixin;

import dev.primept.capture.DynamicTextures;
import dev.primept.capture.CanonicalDynamicTexture;
import dev.primept.capture.CanonicalTextureSources;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DynamicTexture.class)
public abstract class DynamicTextureMixin
        extends net.minecraft.client.renderer.texture.AbstractTexture
        implements CanonicalDynamicTexture {
    @Shadow private NativeImage pixels;

    @Unique private boolean primept$uploaded;
    @Unique private volatile boolean primept$exposed;

    @Inject(method = "upload", at = @At("RETURN"))
    private void primept$sourcePixels(CallbackInfo callback) {
        if (texture == null)
            return;
        primept$uploaded = true;
        DynamicTextures.image(texture, pixels);
    }
    @Inject(method = "getPixels", at = @At("HEAD"))
    private void primept$externalPixels(CallbackInfoReturnable<NativeImage> callback) {
        if (!CanonicalTextureSources.isMapRead((DynamicTexture)(Object)this))
            primept$exposed = true;
    }
    @Inject(method = "setPixels", at = @At("HEAD"))
    private void primept$replacedPixels(NativeImage replacement, CallbackInfo callback) {
        primept$exposed = true;
    }
    @Override
    public NativeImage primept$peekPixels() {
        return pixels;
    }
    @Override
    public boolean primept$hasUploadedPixels() {
        return primept$uploaded;
    }
    @Override
    public boolean primept$pixelsExposed() {
        return primept$exposed;
    }
}
