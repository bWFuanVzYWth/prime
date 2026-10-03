package dev.primept.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.primept.HostVulkanRenderer;
import dev.primept.StreamlineFrames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceLifecycleMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void primept$shutdown(CallbackInfo ci) {
        HostVulkanRenderer.shutdownHostDevice();
        StreamlineFrames.shutdown();
    }
}
