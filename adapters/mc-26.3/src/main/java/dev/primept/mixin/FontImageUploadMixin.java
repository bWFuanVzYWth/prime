package dev.primept.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.primept.capture.FontGlyphSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.mojang.blaze3d.platform.NativeImage;
@Mixin(targets = {"com.mojang.blaze3d.font.TrueTypeGlyphProvider$Glyph$1",
                  "net.minecraft.client.gui.font.glyphs.SpecialGlyphs$1"})
public abstract class FontImageUploadMixin {
    @WrapOperation(
            method = "upload",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/renderpearl/api/commands/CommandEncoder;writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIII)V"))
    private void
    primept$upload(CommandEncoder encoder, GpuTexture texture, NativeImage image, int mip,
                   int layer, int x, int y, Operation<Void> original) {
        original.call(encoder, texture, image, mip, layer, x, y);
        FontGlyphSources.image(texture, image, mip, layer, x, y);
    }
}
