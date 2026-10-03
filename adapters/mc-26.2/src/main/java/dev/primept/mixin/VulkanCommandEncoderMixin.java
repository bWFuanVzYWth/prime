package dev.primept.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.primept.HostVulkanRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Queue acceptance precedes submit's old-slot wait and destroy's device-idle wait. */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin {
    @WrapMethod(method = {"submit", "destroy"})
    private void primept$submissionFailure(Operation<Void> original) {
        try {
            original.call();
        } catch (RuntimeException | Error failure) {
            HostVulkanRenderer.submissionFailed(this, failure);
            throw failure;
        }
    }

    @WrapOperation(method = {"submit", "destroy"},
                   at = @At(value = "INVOKE",
                            target = "Lcom/mojang/blaze3d/vulkan/VulkanQueue$Submission;close()V"))
    private void primept$accepted(VulkanQueue.Submission submission, Operation<Void> original) {
        long serial = ((VulkanCommandEncoderAccessor)(Object)this).primept$currentSubmitIndex();
        try {
            original.call(submission);
        } catch (RuntimeException | Error failure) {
            HostVulkanRenderer.submissionFailed(this, failure);
            throw failure;
        }
        HostVulkanRenderer.submissionAccepted(this, serial);
    }
}
