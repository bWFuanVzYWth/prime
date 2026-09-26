package dev.primept.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.primept.HostVulkanRenderer;
import dev.primept.VulkanBootstrap;
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
        if (!Boolean.getBoolean("primept.enabled"))
            return usage;
        var backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).primept$backend();
        return backend instanceof VulkanDevice device && VulkanBootstrap.isEnabled(device)
                ? usage | HostVulkanRenderer.USAGE_STORAGE
                : usage;
    }
}
