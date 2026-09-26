package dev.primept;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRDeferredHostOperations;
import org.lwjgl.vulkan.KHRRayQuery;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
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
            new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "bufferDeviceAddress",
                              VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS),
            new VulkanFeature(
                    new VulkanPNextStruct(
                            KHRAccelerationStructure
                                    .VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
                            VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF),
                    "accelerationStructure",
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE),
            new VulkanFeature(
                    new VulkanPNextStruct(
                            KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR,
                            VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF),
                    "rayQuery", VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY));

    // Device creation publishes one immutable result; the render thread only observes it.
    private static volatile Status status =
            new Status(0, 0, false, "Host Vulkan device has not been negotiated");

    private VulkanBootstrap() {}

    public static void negotiate(Collection<String> extensions, VulkanPhysicalDevice physical,
                                 Set<VulkanFeature> features) {
        long physicalHandle = physical.vkPhysicalDevice().address();
        if (!Boolean.getBoolean("primept.enabled")) {
            status = new Status(physicalHandle, 0, false, "Prime PT is disabled");
            return;
        }
        var missing = new ArrayList<String>();
        if (physical.vkPhysicalDeviceProperties().apiVersion() < VK12.VK_API_VERSION_1_2)
            missing.add("Vulkan 1.2");
        for (String extension : EXTENSIONS)
            if (!physical.hasDeviceExtension(extension))
                missing.add(extension);
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
                VK12.vkGetPhysicalDeviceFeatures2(physical.vkPhysicalDevice(), available);
                if (!address.bufferDeviceAddress())
                    missing.add("bufferDeviceAddress");
                if (!acceleration.accelerationStructure())
                    missing.add("accelerationStructure");
                if (!query.rayQuery())
                    missing.add("rayQuery");

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
            status = new Status(physicalHandle, 0, false, reason);
            LOGGER.warn("Prime PT host Vulkan integration unavailable: {}", reason);
            return;
        }
        extensions.addAll(EXTENSIONS);
        features.addAll(FEATURES);
        status = new Status(physicalHandle, 0, true,
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
        status = new Status(previous.physical, device.address(), true, "");
        LOGGER.info(
                "Prime PT enabled rayQuery, accelerationStructure and bufferDeviceAddress on Minecraft's Vulkan device");
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

    private record Status(long physical, long device, boolean requested, String reason) {}
}
