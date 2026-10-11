package dev.primept.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.primept.capture.FontGlyphSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
@Mixin(targets = "net.minecraft.client.gui.font.providers.BitmapProvider$Glyph$1")
public abstract class FontBitmapUploadMixin {
    @WrapOperation(
            method = "upload",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/renderpearl/api/commands/CommandEncoder;copyBufferToTexture(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;IIIILcom/mojang/renderpearl/api/textures/GpuTexture;IIIIII)V"))
    private void
    primept$upload(CommandEncoder encoder, GpuBufferSlice source, int sx, int sy, int sw, int sh,
                   GpuTexture texture, int x, int y, int width, int height, int mip, int layer,
                   Operation<Void> original) {
        original.call(encoder, source, sx, sy, sw, sh, texture, x, y, width, height, mip, layer);
        FontGlyphSources.copy(source, sx, sy, sw, sh, texture, x, y, width, height, mip, layer);
    }
}
