package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.EXTPrivateData.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRIVATE_DATA_FEATURES_EXT;

public final class VulkanDeviceNegotiator {
    private static final List<String> REQUIRED_EXTENSIONS = List.of(
            KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);

    private static final List<String> FIDELITY_FX_BACKEND_EXTENSIONS = List.of(
            KHRGetMemoryRequirements2.VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
            KHRDedicatedAllocation.VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME);

    private static final VulkanPNextStruct ACCELERATION_STRUCTURE_FEATURES = new VulkanPNextStruct(
            KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
            VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct RAY_TRACING_PIPELINE_FEATURES = new VulkanPNextStruct(
            KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR,
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct INVOCATION_REORDER_FEATURES = new VulkanPNextStruct(
            EXTRayTracingInvocationReorder
                    .VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_EXT,
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.SIZEOF);
    private static final VulkanPNextStruct OPACITY_MICROMAP_FEATURES = new VulkanPNextStruct(
            EXTOpacityMicromap.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT,
            VkPhysicalDeviceOpacityMicromapFeaturesEXT.SIZEOF);
    private static final VulkanPNextStruct PRIVATE_DATA_FEATURES = new VulkanPNextStruct(
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRIVATE_DATA_FEATURES_EXT,
            VkPhysicalDevicePrivateDataFeaturesEXT.SIZEOF);

    private static final VulkanFeature SHADER_INT64 = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT,
            "shaderInt64",
            VkPhysicalDeviceFeatures.SHADERINT64);
    private static final VulkanFeature SHADER_INT16 = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT,
            "shaderInt16",
            VkPhysicalDeviceFeatures.SHADERINT16);
    private static final VulkanFeature STORAGE_IMAGE_EXTENDED_FORMATS = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT,
            "shaderStorageImageExtendedFormats",
            VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEEXTENDEDFORMATS);
    private static final VulkanFeature STORAGE_IMAGE_READ_WITHOUT_FORMAT = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT,
            "shaderStorageImageReadWithoutFormat",
            VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEREADWITHOUTFORMAT);
    private static final VulkanFeature STORAGE_IMAGE_WRITE_WITHOUT_FORMAT = new VulkanFeature(
            VulkanBackend.VK10_FEATURES_STRUCT,
            "shaderStorageImageWriteWithoutFormat",
            VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEWRITEWITHOUTFORMAT);
    private static final VulkanFeature BUFFER_DEVICE_ADDRESS = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "bufferDeviceAddress",
            VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS);
    private static final VulkanFeature SAMPLED_IMAGE_ARRAY_NON_UNIFORM_INDEXING =
            new VulkanFeature(
                    VulkanBackend.VK12_FEATURES_STRUCT,
                    "shaderSampledImageArrayNonUniformIndexing",
                    VkPhysicalDeviceVulkan12Features
                            .SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING);
    private static final VulkanFeature STORAGE_BUFFER_16_BIT_ACCESS = new VulkanFeature(
            VulkanBackend.VK11_FEATURES_STRUCT,
            "storageBuffer16BitAccess",
            VkPhysicalDeviceVulkan11Features.STORAGEBUFFER16BITACCESS);
    private static final VulkanFeature SHADER_FLOAT16 = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "shaderFloat16",
            VkPhysicalDeviceVulkan12Features.SHADERFLOAT16);
    private static final VulkanFeature SHADER_SUBGROUP_EXTENDED_TYPES = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT,
            "shaderSubgroupExtendedTypes",
            VkPhysicalDeviceVulkan12Features.SHADERSUBGROUPEXTENDEDTYPES);
    private static final VulkanFeature ACCELERATION_STRUCTURE = new VulkanFeature(
            ACCELERATION_STRUCTURE_FEATURES,
            "accelerationStructure",
            VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE);
    private static final VulkanFeature RAY_TRACING_PIPELINE = new VulkanFeature(
            RAY_TRACING_PIPELINE_FEATURES,
            "rayTracingPipeline",
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE);
    private static final VulkanFeature RAY_TRACING_PIPELINE_INDIRECT = new VulkanFeature(
            RAY_TRACING_PIPELINE_FEATURES,
            "rayTracingPipelineTraceRaysIndirect",
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINETRACERAYSINDIRECT);
    private static final VulkanFeature INVOCATION_REORDER = new VulkanFeature(
            INVOCATION_REORDER_FEATURES,
            "rayTracingInvocationReorder",
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.RAYTRACINGINVOCATIONREORDER);
    private static final VulkanFeature OPACITY_MICROMAP = new VulkanFeature(
            OPACITY_MICROMAP_FEATURES,
            "micromap",
            VkPhysicalDeviceOpacityMicromapFeaturesEXT.MICROMAP);
    private static final VulkanFeature PRIVATE_DATA = new VulkanFeature(
            PRIVATE_DATA_FEATURES,
            "privateData",
            VkPhysicalDevicePrivateDataFeaturesEXT.PRIVATEDATA);

    private VulkanDeviceNegotiator() {
    }

    public static VulkanCapabilities negotiate(
            VulkanPhysicalDevice physicalDevice,
            Collection<String> enabledExtensions,
            Set<VulkanFeature> enabledFeatures) {
        String deviceName = physicalDevice.deviceName();
        List<String> missing = new ArrayList<>();
        boolean invocationReorderExtension = physicalDevice.hasDeviceExtension(
                EXTRayTracingInvocationReorder
                        .VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
        boolean opacityMicromapExtension = physicalDevice.hasDeviceExtension(
                EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
        boolean privateDataExtension = physicalDevice.hasDeviceExtension(
                EXTPrivateData.VK_EXT_PRIVATE_DATA_EXTENSION_NAME);
        for (String extension : REQUIRED_EXTENSIONS) {
            if (!physicalDevice.hasDeviceExtension(extension)) {
                missing.add(extension);
            }
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan11Features vulkan11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan12Features vulkan12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            VkPhysicalDeviceAccelerationStructureFeaturesKHR acceleration =
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracing =
                    VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT invocationReorder =
                    VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.calloc(stack)
                            .sType$Default();
            VkPhysicalDeviceOpacityMicromapFeaturesEXT opacityMicromap =
                    VkPhysicalDeviceOpacityMicromapFeaturesEXT.calloc(stack).sType$Default();
            VkPhysicalDevicePrivateDataFeaturesEXT privateDataFeatures =
                    VkPhysicalDevicePrivateDataFeaturesEXT.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRIVATE_DATA_FEATURES_EXT);
            features.pNext(vulkan11.address());
            vulkan11.pNext(vulkan12.address());
            vulkan12.pNext(acceleration.address());
            acceleration.pNext(rayTracing.address());
            long optionalFeatureChain = 0L;
            if (invocationReorderExtension) {
                invocationReorder.pNext(optionalFeatureChain);
                optionalFeatureChain = invocationReorder.address();
            }
            if (opacityMicromapExtension) {
                opacityMicromap.pNext(optionalFeatureChain);
                optionalFeatureChain = opacityMicromap.address();
            }
            if (privateDataExtension) {
                privateDataFeatures.pNext(optionalFeatureChain);
                optionalFeatureChain = privateDataFeatures.address();
            }
            rayTracing.pNext(optionalFeatureChain);
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), features);

            if (!features.features().shaderInt64()) {
                missing.add("shaderInt64");
            }
            if (!features.features().shaderStorageImageExtendedFormats()) {
                missing.add("shaderStorageImageExtendedFormats");
            }
            if (!features.features().shaderStorageImageReadWithoutFormat()) {
                missing.add("shaderStorageImageReadWithoutFormat");
            }
            if (!features.features().shaderStorageImageWriteWithoutFormat()) {
                missing.add("shaderStorageImageWriteWithoutFormat");
            }
            if (!vulkan12.bufferDeviceAddress()) {
                missing.add("bufferDeviceAddress");
            }
            if (!vulkan12.shaderSampledImageArrayNonUniformIndexing()) {
                missing.add("shaderSampledImageArrayNonUniformIndexing");
            }
            if (!acceleration.accelerationStructure()) {
                missing.add("accelerationStructure");
            }
            if (!rayTracing.rayTracingPipeline()) {
                missing.add("rayTracingPipeline");
            }
            if (!rayTracing.rayTracingPipelineTraceRaysIndirect()) {
                missing.add("rayTracingPipelineTraceRaysIndirect");
            }

            if (!missing.isEmpty()) {
                return VulkanCapabilities.unavailable(deviceName, "Missing Vulkan capabilities: " + String.join(", ", missing));
            }

            VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            VkPhysicalDeviceSubgroupProperties subgroupProperties =
                    VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR rayProperties =
                    VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceAccelerationStructurePropertiesKHR accelerationProperties =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceRayTracingInvocationReorderPropertiesEXT invocationReorderProperties =
                    VkPhysicalDeviceRayTracingInvocationReorderPropertiesEXT.calloc(stack)
                            .sType$Default();
            VkPhysicalDeviceOpacityMicromapPropertiesEXT opacityMicromapProperties =
                    VkPhysicalDeviceOpacityMicromapPropertiesEXT.calloc(stack).sType$Default();
            properties.pNext(subgroupProperties.address());
            subgroupProperties.pNext(rayProperties.address());
            rayProperties.pNext(accelerationProperties.address());
            long optionalPropertyChain = 0L;
            if (invocationReorderExtension) {
                invocationReorderProperties.pNext(optionalPropertyChain);
                optionalPropertyChain = invocationReorderProperties.address();
            }
            if (opacityMicromapExtension) {
                opacityMicromapProperties.pNext(optionalPropertyChain);
                optionalPropertyChain = opacityMicromapProperties.address();
            }
            accelerationProperties.pNext(optionalPropertyChain);
            VK12.vkGetPhysicalDeviceProperties2(physicalDevice.vkPhysicalDevice(), properties);

            var limits = properties.properties().limits();
            if (!supportsSceneTextureDescriptors(
                    limits.maxPerStageDescriptorSamplers(),
                    limits.maxPerStageDescriptorSampledImages(),
                    limits.maxDescriptorSetSamplers(),
                    limits.maxDescriptorSetSampledImages())) {
                return VulkanCapabilities.unavailable(
                        deviceName,
                        "Insufficient sampler or sampled-image descriptors for dynamic scene textures");
            }
            if (rayProperties.maxRayRecursionDepth() < 1) {
                return VulkanCapabilities.unavailable(deviceName, "Ray tracing recursion depth 1 is not supported");
            }
            VkFormatProperties srgbColorFormat = VkFormatProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceFormatProperties(
                    physicalDevice.vkPhysicalDevice(),
                    VK12.VK_FORMAT_R8G8B8A8_SRGB,
                    srgbColorFormat);
            int requiredSrgbColorFeatures = VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                    | VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if ((srgbColorFormat.optimalTilingFeatures() & requiredSrgbColorFeatures)
                    != requiredSrgbColorFeatures) {
                return VulkanCapabilities.unavailable(
                        deviceName,
                        "RGBA8 sRGB linear filtering required for albedo textures is not supported");
            }
            VkFormatProperties accumulationFormat = VkFormatProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceFormatProperties(
                    physicalDevice.vkPhysicalDevice(),
                    VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    accumulationFormat);
            if ((accumulationFormat.optimalTilingFeatures() & VK12.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
                return VulkanCapabilities.unavailable(
                        deviceName,
                        "RGBA32F storage images required for path accumulation are not supported");
            }
            VkFormatProperties bsdfLookupFormat = VkFormatProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceFormatProperties(
                    physicalDevice.vkPhysicalDevice(),
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    bsdfLookupFormat);
            int requiredBsdfLookupFeatures = VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                    | VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if ((bsdfLookupFormat.optimalTilingFeatures() & requiredBsdfLookupFeatures)
                    != requiredBsdfLookupFeatures) {
                return VulkanCapabilities.unavailable(
                        deviceName,
                        "RGBA16F linearly filtered images required for the BSDF lookup are not supported");
            }
            VkFormatProperties exactNormalFormat = VkFormatProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceFormatProperties(
                    physicalDevice.vkPhysicalDevice(),
                    VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    exactNormalFormat);
            int requiredExactNormalFeatures =
                    VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK12.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
            if ((exactNormalFormat.optimalTilingFeatures() & requiredExactNormalFeatures)
                    != requiredExactNormalFeatures) {
                return VulkanCapabilities.unavailable(
                        deviceName,
                        "RGBA32F sampled/storage images required for exact guide normals are not supported");
            }
            if (rayProperties.shaderGroupHandleSize() <= 0
                    || rayProperties.maxShaderGroupStride() == 0
                    || rayProperties.maxRayDispatchInvocationCount() == 0
                    || accelerationProperties.maxPrimitiveCount() == 0L
                    || accelerationProperties.maxInstanceCount() == 0L
                    || Long.compareUnsigned(
                                    accelerationProperties.maxGeometryCount(), 3L)
                            < 0
                    || !isPositivePowerOfTwo(rayProperties.shaderGroupHandleAlignment())
                    || !isPositivePowerOfTwo(rayProperties.shaderGroupBaseAlignment())
                    || !isPositivePowerOfTwo(accelerationProperties.minAccelerationStructureScratchOffsetAlignment())) {
                return VulkanCapabilities.unavailable(deviceName, "Vulkan reported invalid ray tracing alignment properties");
            }

            enabledExtensions.addAll(REQUIRED_EXTENSIONS);
            DlssRrBootstrap.enableRequiredDeviceExtensions(
                    physicalDevice, enabledExtensions);

            // FidelityFX SDK 1.1.4 decides whether to use dedicated allocations from the
            // physical device's advertised extension list, not the logical device's enabled
            // extension list. If these promoted Vulkan 1.1 extensions are advertised but not
            // explicitly enabled, its backend still calls vkGetBufferMemoryRequirements2KHR;
            // vkGetDeviceProcAddr may legally return null and the signed DLL calls through that
            // pointer during context creation. Keep the pair enabled together because
            // VK_KHR_dedicated_allocation depends on VK_KHR_get_memory_requirements2.
            if (physicalDevice.hasDeviceExtension(
                            KHRDedicatedAllocation.VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME)
                    && physicalDevice.hasDeviceExtension(
                            KHRGetMemoryRequirements2
                                    .VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME)) {
                enabledExtensions.addAll(FIDELITY_FX_BACKEND_EXTENSIONS);
            }
            enabledFeatures.add(SHADER_INT64);
            enabledFeatures.add(STORAGE_IMAGE_EXTENDED_FORMATS);
            enabledFeatures.add(STORAGE_IMAGE_READ_WITHOUT_FORMAT);
            enabledFeatures.add(STORAGE_IMAGE_WRITE_WITHOUT_FORMAT);
            enabledFeatures.add(BUFFER_DEVICE_ADDRESS);
            enabledFeatures.add(SAMPLED_IMAGE_ARRAY_NON_UNIFORM_INDEXING);
            enabledFeatures.add(ACCELERATION_STRUCTURE);
            enabledFeatures.add(RAY_TRACING_PIPELINE);
            enabledFeatures.add(RAY_TRACING_PIPELINE_INDIRECT);

            // The FidelityFX DLL owns its FP32/FP16 permutations, but it cannot use an optional
            // physical-device feature unless Minecraft enabled it on the logical device. Expose
            // the complete conservative 16-bit set only when all members are supported. The DLL
            // remains free to use FP32, and this optional path never affects RT availability.
            boolean fsrFp16Supported = vulkan11.storageBuffer16BitAccess()
                    && features.features().shaderInt16()
                    && vulkan12.shaderFloat16()
                    && vulkan12.shaderSubgroupExtendedTypes();
            if (fsrFp16Supported) {
                enabledFeatures.add(STORAGE_BUFFER_16_BIT_ACCESS);
                enabledFeatures.add(SHADER_INT16);
                enabledFeatures.add(SHADER_FLOAT16);
                enabledFeatures.add(SHADER_SUBGROUP_EXTENDED_TYPES);
            }

            // EXT deliberately permits hit objects without real reordering. Prime loads its SER
            // permutation only when the driver advertises both the feature and REORDER mode; a
            // no-op implementation would add live-state save/restore structure without solving
            // the divergence this path exists for. The reported record-index limit governs the
            // explicit hit-object override instruction, not the number of BLAS geometries; Prime
            // does not issue that instruction and retains its existing conservative index-1 check.
            // LWJGL owns the shared EXT/NV reorder-mode enum alias in its older NV binding class;
            // Prime does not query or enable VK_NV_ray_tracing_invocation_reorder.
            boolean invocationReorderSupported = invocationReorderExtension
                    && invocationReorder.rayTracingInvocationReorder()
                    && invocationReorderProperties.rayTracingInvocationReorderReorderingHint()
                            == NVRayTracingInvocationReorder
                                    .VK_RAY_TRACING_INVOCATION_REORDER_MODE_REORDER_EXT
                    && supportsSbtRecordIndex(
                            invocationReorderProperties.maxShaderBindingTableRecordIndex(), 1);
            boolean wavefrontSubgroupSupported = supportsWavefrontSubgroups(
                    subgroupProperties.supportedStages(),
                    subgroupProperties.supportedOperations());
            if (invocationReorderSupported) {
                enabledExtensions.add(EXTRayTracingInvocationReorder
                        .VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
                enabledFeatures.add(INVOCATION_REORDER);
            }

            boolean opacityMicromapSupported = opacityMicromapExtension
                    && opacityMicromap.micromap()
                    && opacityMicromapProperties.maxOpacity2StateSubdivisionLevel()
                            >= dev.prime.render.terrain.OpacityMicromapData.SUBDIVISION_LEVEL;
            if (opacityMicromapSupported) {
                enabledExtensions.add(EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
                enabledFeatures.add(OPACITY_MICROMAP);
            }

            // The Streamline interposer required it
            if (privateDataExtension && privateDataFeatures.privateData()) {
                enabledExtensions.add(EXTPrivateData.VK_EXT_PRIVATE_DATA_EXTENSION_NAME);
                enabledFeatures.add(PRIVATE_DATA);
            }

            return new VulkanCapabilities(
                    true,
                    deviceName,
                    "",
                    rayProperties.shaderGroupHandleSize(),
                    rayProperties.shaderGroupHandleAlignment(),
                    rayProperties.shaderGroupBaseAlignment(),
                    rayProperties.maxShaderGroupStride(),
                    rayProperties.maxRayDispatchInvocationCount(),
                    rayProperties.maxRayRecursionDepth(),
                    accelerationProperties.maxPrimitiveCount(),
                    accelerationProperties.maxInstanceCount(),
                    accelerationProperties.minAccelerationStructureScratchOffsetAlignment(),
                    wavefrontSubgroupSupported,
                    invocationReorderSupported,
                    opacityMicromapSupported,
                    opacityMicromapSupported
                            ? opacityMicromapProperties.maxOpacity2StateSubdivisionLevel()
                            : 0,
                    opacityMicromapSupported
                            ? opacityMicromapProperties.maxOpacity4StateSubdivisionLevel()
                            : 0,
                    fsrFp16Supported);
        }
    }

    private static boolean isPositivePowerOfTwo(int value) {
        return value > 0 && (value & value - 1) == 0;
    }

    static boolean supportsWavefrontSubgroups(
            int supportedStages,
            int supportedOperations) {
        int requiredOperations =
                VK11.VK_SUBGROUP_FEATURE_BASIC_BIT
                        | VK11.VK_SUBGROUP_FEATURE_BALLOT_BIT;
        return (supportedStages
                        & KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR)
                != 0
                && (supportedOperations & requiredOperations)
                        == requiredOperations;
    }

    static boolean supportsSbtRecordIndex(int unsignedMaximum, int requiredIndex) {
        if (requiredIndex < 0) {
            throw new IllegalArgumentException("SBT record index must be non-negative");
        }
        return Integer.compareUnsigned(unsignedMaximum, requiredIndex) >= 0;
    }

    static boolean supportsSceneTextureDescriptors(
            int perStageSamplerLimit,
            int perStageSampledImageLimit,
            int descriptorSetSamplerLimit,
            int descriptorSetSampledImageLimit) {
        int required = dev.prime.render.shader.ShaderAbi.SCENE_TEXTURE_COUNT
                + 2 * dev.prime.render.shader.ShaderAbi.MATERIAL_PAGE_COUNT
                + 2;
        return Integer.compareUnsigned(perStageSamplerLimit, required) >= 0
                && Integer.compareUnsigned(perStageSampledImageLimit, required) >= 0
                && Integer.compareUnsigned(descriptorSetSamplerLimit, required) >= 0
                && Integer.compareUnsigned(descriptorSetSampledImageLimit, required) >= 0;
    }
}
