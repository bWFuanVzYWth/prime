package dev.primept.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.primept.capture.FontGlyphSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.nio.ByteBuffer;
@Mixin(targets = "net.minecraft.client.gui.font.providers.UnihexProvider$Glyph$2")
public abstract class FontUnihexUploadMixin {
    @WrapOperation(
            method = "upload",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/blaze3d/systems/CommandEncoder;writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V"))
    private void
    primept$upload(CommandEncoder encoder, GpuTexture texture, ByteBuffer pixels, int mip,
                   int layer, int x, int y, int width, int height, Operation<Void> original) {
        ByteBuffer borrowed = pixels.duplicate();
        original.call(encoder, texture, pixels, mip, layer, x, y, width, height);
        FontGlyphSources.bytes(texture, borrowed, mip, layer, x, y, width, height);
    }
}
