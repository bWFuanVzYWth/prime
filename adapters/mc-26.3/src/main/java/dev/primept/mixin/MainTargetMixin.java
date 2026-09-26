package dev.primept.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import dev.primept.HostVulkanRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(MainTarget.class)
public abstract class MainTargetMixin {
    @ModifyArg(
            method = "allocateColorAttachment",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/renderpearl/api/device/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;")
            ,
            index = 1)
    private int
    primept$storageTarget(int usage) {
        return HostVulkanRenderer.mainColorUsage(usage);
    }
}
