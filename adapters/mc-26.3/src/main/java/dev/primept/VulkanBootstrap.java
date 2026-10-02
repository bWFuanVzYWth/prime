package dev.primept;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRDeferredHostOperations;
import org.lwjgl.vulkan.KHRRayQuery;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.EXTOpacityMicromap;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceSynchronization2Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Negotiates before host device creation; a supported physical GPU alone is not an enabled device. */
public final class VulkanBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("PrimePT");
    private static final List<String> EXTENSIONS =
            List.of(KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
                    KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
                    KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME);
    private static final List<VulkanFeature> FEATURES = List.of(
            new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress",
                              VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS),
            new VulkanFeature(
                    new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class),
                    "accelerationStructure",
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE),
            new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class),
                              "rayQuery", VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY));
    private static final VulkanFeature OPACITY_MICROMAP_FEATURE = new VulkanFeature(
            new VulkanPNextStruct(VkPhysicalDeviceOpacityMicromapFeaturesEXT.class), "micromap",
            VkPhysicalDeviceOpacityMicromapFeaturesEXT.MICROMAP);
    private static final VulkanFeature SYNCHRONIZATION_2_FEATURE =
            new VulkanFeature(VulkanFeatureSets.SYNC2_FEATURES_STRUCT, "synchronization2",
                              VkPhysicalDeviceSynchronization2Features.SYNCHRONIZATION2);

    // Device creation publishes one immutable result; the render thread only observes it.
    private static volatile Status status =
            new Status(0, 0, false, false, "Host Vulkan device has not been negotiated");

    private VulkanBootstrap() {}

    public static void negotiate(Collection<String> extensions, VulkanPhysicalDevice physical,
                                 Set<VulkanFeature> features) {
        long physicalHandle = physical.vkPhysicalDevice().address();
        if (!Boolean.getBoolean("primept.enabled")) {
            status = new Status(physicalHandle, 0, false, false, "Prime PT is disabled");
            return;
        }
        var missing = new ArrayList<String>();
        int apiVersion = physical.vkPhysicalDeviceProperties().apiVersion();
        if (apiVersion < VK12.VK_API_VERSION_1_2)
            missing.add("Vulkan 1.2");
        for (String extension : EXTENSIONS)
            if (!physical.hasDeviceExtension(extension))
                missing.add(extension);
        boolean opacityMicromap =
                physical.hasDeviceExtension(
                        EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME) &&
                (apiVersion >= VK13.VK_API_VERSION_1_3 ||
                 physical.hasDeviceExtension(
                         KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME));
        // Vulkan 1.2 supplies the promoted dependencies of acceleration_structure/ray_query.
        if (missing.isEmpty()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var address = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
                var acceleration = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                                           .sType$Default();
                var query = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
                var available = VkPhysicalDeviceFeatures2.calloc(stack)
                                        .sType$Default()
                                        .pNext(address)
                                        .pNext(acceleration)
                                        .pNext(query);
                var micromap =
                        VkPhysicalDeviceOpacityMicromapFeaturesEXT.calloc(stack).sType$Default();
                if (opacityMicromap)
                    available.pNext(micromap);
                VK12.vkGetPhysicalDeviceFeatures2(physical.vkPhysicalDevice(), available);
                if (!address.bufferDeviceAddress())
                    missing.add("bufferDeviceAddress");
                if (!acceleration.accelerationStructure())
                    missing.add("accelerationStructure");
                if (!query.rayQuery())
                    missing.add("rayQuery");
                opacityMicromap &= micromap.micromap();

                var count = stack.mallocInt(1);
                VK12.vkGetPhysicalDeviceQueueFamilyProperties(physical.vkPhysicalDevice(), count,
                                                              null);
                var queues = org.lwjgl.vulkan.VkQueueFamilyProperties.calloc(count.get(0), stack);
                VK12.vkGetPhysicalDeviceQueueFamilyProperties(physical.vkPhysicalDevice(), count,
                                                              queues);
                int graphicsFamily = physical.graphicsQueueFamilyAndIndex().leftInt();
                if ((queues.get(graphicsFamily).queueFlags() & VK12.VK_QUEUE_COMPUTE_BIT) == 0)
                    missing.add("compute operations on the host graphics queue");
            }
        }
        if (!missing.isEmpty()) {
            String reason =
                    "Host GPU " + physical.deviceName() + " lacks " + String.join(", ", missing);
            status = new Status(physicalHandle, 0, false, false, reason);
            LOGGER.warn("Prime PT host Vulkan integration unavailable: {}", reason);
            return;
        }
        extensions.addAll(EXTENSIONS);
        features.addAll(FEATURES);
        if (opacityMicromap) {
            extensions.add(EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
            features.add(OPACITY_MICROMAP_FEATURE);
            if (apiVersion < VK13.VK_API_VERSION_1_3)
                extensions.add(KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME);
        }
        status = new Status(physicalHandle, 0, true, opacityMicromap,
                            "Host Vulkan device creation has not completed");
    }

    /** Called only after Minecraft's vkCreateDevice succeeded with the negotiated collections. */
    public static void deviceCreated(VkDevice device, Collection<String> extensions,
                                     Set<VulkanFeature> features) {
        Status previous = status;
        if (!previous.requested || !Boolean.getBoolean("primept.enabled") ||
            previous.physical != device.getPhysicalDevice().address() ||
            !extensions.containsAll(EXTENSIONS) || !features.containsAll(FEATURES))
            return;
        boolean opacityMicromap =
                previous.opacityMicromap &&
                extensions.contains(EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME) &&
                extensions.contains(KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME) &&
                features.contains(OPACITY_MICROMAP_FEATURE) &&
                features.contains(SYNCHRONIZATION_2_FEATURE);
        status = new Status(previous.physical, device.address(), true, opacityMicromap, "");
        LOGGER.info(
                "Prime PT enabled rayQuery, accelerationStructure and bufferDeviceAddress on Minecraft's Vulkan device");
        LOGGER.info("Prime PT opacity micromaps: {}",
                    opacityMicromap ? "enabled" : "unavailable; alpha test fallback");
    }

    public static boolean isEnabled(VulkanDevice device) {
        Status current = status;
        return current.device != 0 && current.device == device.vkDevice().address() &&
                current.physical == device.vkDevice().getPhysicalDevice().address();
    }

    public static void requireEnabled(VulkanDevice device) {
        if (!isEnabled(device)) {
            String reason = unavailableReason();
            throw new IllegalStateException(
                    reason.isEmpty() ? "Prime PT was not negotiated for this host Vulkan device"
                                     : reason);
        }
    }

    public static String unavailableReason() {
        return status.reason;
    }
    /** Reports the feature enabled on this exact logical device, never physical support alone. */
    public static boolean opacityMicromapEnabled(VulkanDevice device) {
        return isEnabled(device) && status.opacityMicromap;
    }

    private record Status(long physical, long device, boolean requested, boolean opacityMicromap,
                          String reason) {}
}
