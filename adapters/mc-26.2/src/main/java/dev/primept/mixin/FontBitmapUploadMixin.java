package dev.primept.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.primept.capture.FontGlyphSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
@Mixin(targets = "net.minecraft.client.gui.font.providers.BitmapProvider$Glyph$1")
public abstract class FontBitmapUploadMixin {
    @WrapOperation(
            method = "upload",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/blaze3d/systems/CommandEncoder;copyBufferToTexture(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;IIIILcom/mojang/blaze3d/textures/GpuTexture;IIIIII)V"))
    private void
    primept$upload(CommandEncoder encoder, GpuBufferSlice source, int sx, int sy, int sw, int sh,
                   GpuTexture texture, int x, int y, int width, int height, int mip, int layer,
                   Operation<Void> original) {
        original.call(encoder, source, sx, sy, sw, sh, texture, x, y, width, height, mip, layer);
        FontGlyphSources.copy(source, sx, sy, sw, sh, texture, x, y, width, height, mip, layer);
    }
}
