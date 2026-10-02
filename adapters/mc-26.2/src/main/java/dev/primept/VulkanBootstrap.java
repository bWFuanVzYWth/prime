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
    private static final VulkanFeature OPACITY_MICROMAP_FEATURE = new VulkanFeature(
            new VulkanPNextStruct(
                    EXTOpacityMicromap
                            .VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT,
                    VkPhysicalDeviceOpacityMicromapFeaturesEXT.SIZEOF),
            "micromap", VkPhysicalDeviceOpacityMicromapFeaturesEXT.MICROMAP);
    private static final VulkanFeature SYNCHRONIZATION_2_FEATURE =
            new VulkanFeature(VulkanBackend.SYNC2_FEATURES_STRUCT, "synchronization2",
                              VkPhysicalDeviceSynchronization2Features.SYNCHRONIZATION2);

    // Core Vulkan 1.2 replaces SL's legacy EXT_buffer_device_address requirement.
    // Enabling that extension with VkPhysicalDeviceVulkan12Features would violate Vulkan.
    private static final List<String> STREAMLINE_EXTENSIONS =
            List.of("VK_NVX_binary_import", "VK_NVX_image_view_handle", "VK_KHR_push_descriptor",
                    "VK_KHR_buffer_device_address",
                    KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME);
    private static final List<VulkanFeature> STREAMLINE_FEATURES = List.of(
            SYNCHRONIZATION_2_FEATURE,
            new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "timelineSemaphore",
                              VkPhysicalDeviceVulkan12Features.TIMELINESEMAPHORE),
            new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, "descriptorIndexing",
                              VkPhysicalDeviceVulkan12Features.DESCRIPTORINDEXING),
            new VulkanFeature(
                    VulkanBackend.VK10_FEATURES_STRUCT, "shaderStorageImageExtendedFormats",
                    org.lwjgl.vulkan.VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEEXTENDEDFORMATS),
            // Streamline's Vulkan clear kernel requires this beyond slGetFeatureRequirements.
            new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,
                              "shaderStorageImageWriteWithoutFormat",
                              org.lwjgl.vulkan.VkPhysicalDeviceFeatures
                                      .SHADERSTORAGEIMAGEWRITEWITHOUTFORMAT));

    // Device creation publishes one immutable result; the render thread only observes it.
    private static volatile Status status =
            new Status(0, 0, false, false, false, "Host Vulkan device has not been negotiated");

    private VulkanBootstrap() {}

    public static void negotiate(Collection<String> extensions, VulkanPhysicalDevice physical,
                                 Set<VulkanFeature> features) {
        long physicalHandle = physical.vkPhysicalDevice().address();
        if (!StartupOptions.enabled()) {
            status = new Status(physicalHandle, 0, false, false, false, "Prime PT is disabled");
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
        boolean streamline = System.getProperty("os.name", "")
                                     .toLowerCase(java.util.Locale.ROOT)
                                     .startsWith("windows") &&
                             STREAMLINE_EXTENSIONS.stream().allMatch(physical::hasDeviceExtension);
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
                var synchronization =
                        VkPhysicalDeviceSynchronization2Features.calloc(stack).sType$Default();
                if (streamline)
                    available.pNext(synchronization);
                VK12.vkGetPhysicalDeviceFeatures2(physical.vkPhysicalDevice(), available);
                if (!address.bufferDeviceAddress())
                    missing.add("bufferDeviceAddress");
                if (!acceleration.accelerationStructure())
                    missing.add("accelerationStructure");
                if (!query.rayQuery())
                    missing.add("rayQuery");
                opacityMicromap &= micromap.micromap();
                streamline &= synchronization.synchronization2() && address.timelineSemaphore() &&
                              address.descriptorIndexing() &&
                              available.features().shaderStorageImageExtendedFormats() &&
                              available.features().shaderStorageImageWriteWithoutFormat();

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
            status = new Status(physicalHandle, 0, false, false, false, reason);
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
        if (streamline) {
            extensions.addAll(STREAMLINE_EXTENSIONS);
            features.addAll(STREAMLINE_FEATURES);
        }
        status = new Status(physicalHandle, 0, true, opacityMicromap, streamline,
                            "Host Vulkan device creation has not completed");
    }

    /** Called only after Minecraft's vkCreateDevice succeeded with the negotiated collections. */
    public static void deviceCreated(VkDevice device, Collection<String> extensions,
                                     Set<VulkanFeature> features) {
        Status previous = status;
        if (!previous.requested || !StartupOptions.enabled() ||
            previous.physical != device.getPhysicalDevice().address() ||
            !extensions.containsAll(EXTENSIONS) || !features.containsAll(FEATURES))
            return;
        boolean opacityMicromap =
                previous.opacityMicromap &&
                extensions.contains(EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME) &&
                extensions.contains(KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME) &&
                features.contains(OPACITY_MICROMAP_FEATURE) &&
                features.contains(SYNCHRONIZATION_2_FEATURE);
        boolean streamline = previous.streamline && extensions.containsAll(STREAMLINE_EXTENSIONS) &&
                             features.containsAll(STREAMLINE_FEATURES);
        status = new Status(previous.physical, device.address(), true, opacityMicromap, streamline,
                            "");
        LOGGER.info(
                "Prime PT enabled rayQuery, accelerationStructure and bufferDeviceAddress on Minecraft's Vulkan device");
        LOGGER.info("Prime PT opacity micromaps: {}",
                    opacityMicromap ? "enabled" : "unavailable; alpha test fallback");
        LOGGER.info("Prime PT Streamline device capabilities: {}",
                    streamline ? "enabled; DLSS RR runtime support will be checked on attach"
                               : "unavailable; noisy output fallback");
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

    public static boolean streamlineEnabled(VulkanDevice device) {
        return isEnabled(device) && status.streamline;
    }

    private record Status(long physical, long device, boolean requested, boolean opacityMicromap,
                          boolean streamline, String reason) {}
}
