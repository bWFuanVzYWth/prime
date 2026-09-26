package dev.primept.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.primept.HostVulkanRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {
    // MainTarget inherits resize/createBuffers; its allocateColorAttachment is constructor-only.
    // The first createTexture allocates depth, the second allocates color.
    @ModifyArg(
            method = "createBuffers",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/renderpearl/api/device/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;",
                    ordinal = 1),
            index = 1)
    private int
    primept$mainColorStorage(int usage) {
        return (Object)this instanceof MainTarget ? HostVulkanRenderer.mainColorUsage(usage)
                                                  : usage;
    }
}
