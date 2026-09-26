package dev.primept.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
    @Accessor("submitSemaphore") long primept$submitSemaphore();
    @Accessor("currentSubmitIndex") long primept$currentSubmitIndex();
}
