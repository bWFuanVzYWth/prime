package dev.primept.mixin;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.primept.capture.FontGlyphSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets = "net.minecraft.client.gui.font.providers.BitmapProvider$ImageDataHolder")
public abstract class FontBitmapSourceMixin {
    @Shadow @Final private NativeImage image;
    @Inject(method = "gpuData", at = @At("RETURN"))
    private void primept$source(CallbackInfoReturnable<GpuBuffer> callback) {
        FontGlyphSources.bitmap(callback.getReturnValue(), image);
    }
}
