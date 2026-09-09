// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.vulkan.VulkanBootstrap;
import dev.prime.render.vulkan.VulkanCapabilities;
import dev.prime.render.vulkan.VulkanDeviceNegotiator;
import java.util.Collection;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    private static final String CREATE_DEVICE_DESCRIPTOR =
            "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;";

    @Unique
    private VulkanBootstrap.Negotiation prime$negotiation;

    @ModifyArgs(
            method = CREATE_DEVICE_DESCRIPTOR,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"))
    private void prime$negotiateRayTracing(Args args) {
        Collection<String> extensions = args.get(0);
        VulkanPhysicalDevice physicalDevice = args.get(1);
        Set<VulkanFeature> features = args.get(2);
        VulkanBootstrap.Negotiation negotiation = VulkanBootstrap.beginNegotiation();
        VulkanCapabilities capabilities = VulkanDeviceNegotiator.negotiate(physicalDevice, extensions, features);
        VulkanBootstrap.recordNegotiation(negotiation, capabilities);
        this.prime$negotiation = negotiation;
        if (capabilities.available()) {
            PrimeInfo.LOGGER.info("Enabled Vulkan ray tracing on {}", capabilities.deviceName());
            PrimeInfo.LOGGER.info(
                    "Prime FidelityFX optional FP16 device features: {}",
                    capabilities.fsrFp16Supported() ? "enabled" : "unavailable");
            PrimeInfo.LOGGER.info(
                    "Prime ray tracing invocation reorder: {}",
                    capabilities.invocationReorderSupported()
                            ? "VK_EXT_ray_tracing_invocation_reorder"
                            : "unavailable (standard mega-kernel)");
            PrimeInfo.LOGGER.info(
                    "Prime terrain opacity micromaps: {}",
                    capabilities.opacityMicromapSupported()
                            ? "VK_EXT_opacity_micromap, subdivisions 2-state="
                                    + capabilities.maxOpacity2StateSubdivisionLevel()
                                    + ", 4-state="
                                    + capabilities.maxOpacity4StateSubdivisionLevel()
                            : "unavailable (cutout any-hit fallback)");
        } else {
            PrimeInfo.LOGGER.warn("Prime ray tracing unavailable on {}: {}", capabilities.deviceName(), capabilities.unavailableReason());
        }
    }

    @Inject(method = CREATE_DEVICE_DESCRIPTOR, at = @At("RETURN"))
    private void prime$captureVulkanDevice(
            long window,
            ShaderSource shaderSource,
            GpuDebugOptions debugOptions,
            Runnable criticalShaderLoader,
            CallbackInfoReturnable<GpuDevice> cir) {
        GpuDeviceBackend backend = ((GpuDeviceAccessor) (Object) cir.getReturnValue()).prime$getBackend();
        if (backend instanceof VulkanDevice vulkanDevice) {
            VulkanBootstrap.Negotiation negotiation = this.prime$negotiation;
            if (negotiation == null) {
                throw new IllegalStateException(
                        "Minecraft returned a Vulkan device without a Prime negotiation");
            }
            VulkanBootstrap.attachDevice(negotiation, vulkanDevice);
            this.prime$negotiation = null;
        }
    }
}
