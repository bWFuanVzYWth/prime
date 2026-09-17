// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.shader.ShaderAbi;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkBaseOutStructure;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

final class VulkanDeviceNegotiatorTest {
    @Test
    void negotiatedFeaturesWriteNativeFieldsWithoutOverwritingTheChain() throws IllegalAccessException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var vulkan12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            var root = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(vulkan12);
            for (var field : VulkanDeviceNegotiator.class.getDeclaredFields()) {
                if (field.getType() == VulkanFeature.class) {
                    field.setAccessible(true);
                    VulkanFeature feature = (VulkanFeature) field.get(null);
                    assertFalse(feature.get(root), feature.name());
                    feature.set(root, true, stack);
                    assertTrue(feature.get(root), feature.name());
                }
            }
            assertEquals(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, root.sType());
            // New feature structs may prepend; the existing chain must remain reachable and acyclic.
            var chain = new java.util.HashSet<Long>();
            for (var next = VkBaseOutStructure.createSafe(root.pNext()); next != null; next = next.pNext()) {
                assertTrue(chain.add(next.address()), "Feature chain contains a cycle");
            }
            assertTrue(chain.contains(vulkan12.address()));
            assertTrue(root.features().shaderInt64());
            assertTrue(root.features().shaderInt16());
            assertTrue(root.features().textureCompressionBC());
            assertTrue(root.features().shaderStorageImageExtendedFormats());
            assertTrue(root.features().shaderStorageImageReadWithoutFormat());
            assertTrue(root.features().shaderStorageImageWriteWithoutFormat());
            assertFalse(root.features().geometryShader());
            assertTrue(vulkan12.bufferDeviceAddress());
            assertTrue(vulkan12.shaderSampledImageArrayNonUniformIndexing());
            assertTrue(vulkan12.shaderFloat16());
            assertTrue(vulkan12.shaderSubgroupExtendedTypes());
        }
    }

    @Test
    void wavefrontShaderPermutationUsesScalarFallbackWithoutSer() {
        assertEquals(".rgen.spv",
                VulkanCapabilities.wavefrontShaderSuffix(false, false));
        assertEquals(".rgen.spv",
                VulkanCapabilities.wavefrontShaderSuffix(false, true));
        assertEquals(".rgen.spv",
                VulkanCapabilities.wavefrontShaderSuffix(true, false));
        assertEquals("_ser.rgen.spv",
                VulkanCapabilities.wavefrontShaderSuffix(true, true));
    }

    @Test
    void wavefrontSubgroupsRequireRaygenBallotAndBasicOperations() {
        int raygen = KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
        int required = VK11.VK_SUBGROUP_FEATURE_BASIC_BIT
                | VK11.VK_SUBGROUP_FEATURE_BALLOT_BIT;

        assertTrue(VulkanDeviceNegotiator.supportsWavefrontSubgroups(
                raygen, required));
        assertFalse(VulkanDeviceNegotiator.supportsWavefrontSubgroups(
                0, required));
        assertFalse(VulkanDeviceNegotiator.supportsWavefrontSubgroups(
                raygen, VK11.VK_SUBGROUP_FEATURE_BASIC_BIT));
    }

    @Test
    void sceneTextureDescriptorLimitsAreUnsignedAndCoverBothDescriptorClasses() {
        int required = ShaderAbi.SCENE_TEXTURE_COUNT
                + ShaderAbi.BASE_COLOR_PAGE_COUNT
                + 2 * ShaderAbi.MATERIAL_PAGE_COUNT + 2;

        assertTrue(VulkanDeviceNegotiator.supportsSceneTextureDescriptors(
                -1, -1, -1, -1));
        assertTrue(VulkanDeviceNegotiator.supportsSceneTextureDescriptors(
                required, required, required, required));
        assertFalse(VulkanDeviceNegotiator.supportsSceneTextureDescriptors(
                required - 1, required, required, required));
        assertFalse(VulkanDeviceNegotiator.supportsSceneTextureDescriptors(
                required, required - 1, required, required));
        assertFalse(VulkanDeviceNegotiator.supportsSceneTextureDescriptors(
                required, required, required - 1, required));
        assertFalse(VulkanDeviceNegotiator.supportsSceneTextureDescriptors(
                required, required, required, required - 1));
    }
}
