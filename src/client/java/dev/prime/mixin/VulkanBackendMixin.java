// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.vulkan.VulkanBootstrap;
import dev.prime.render.vulkan.VulkanCapabilities;
import dev.prime.render.vulkan.VulkanDeviceNegotiator;
import java.util.HashSet;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    private static final String CREATE_DEVICE_DESCRIPTOR =
            "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;";

    @Unique
    private VulkanBootstrap.Negotiation prime$negotiation;

    @ModifyExpressionValue(
            method = CREATE_DEVICE_DESCRIPTOR,
            at = @At(
                    value = "NEW",
                    target = "(Ljava/lang/String;Ljava/util/Collection;)Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;"))
    private FeatureSet prime$negotiateRayTracing(
            FeatureSet vanilla, @Local VulkanPhysicalDevice physicalDevice) {
        Set<String> extensions = new HashSet<>(vanilla.extensions());
        Set<VulkanFeature> features = new HashSet<>(vanilla.features());
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
        // The device and its feature metadata must receive the same negotiated set.
        return new FeatureSet(
                vanilla.name(), Set.copyOf(extensions), Set.copyOf(features), vanilla.condition());
    }

    @Inject(method = CREATE_DEVICE_DESCRIPTOR, at = @At("RETURN"))
    private void prime$captureVulkanDevice(
            GpuDebugOptions debugOptions,
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
