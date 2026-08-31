package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.WavefrontShaderPermutation;
import dev.prime.render.shader.ShaderAbi;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK11;

final class VulkanDeviceNegotiatorTest {
    @Test
    void wavefrontShaderPermutationUsesScalarFallbackWithoutSer() {
        assertEquals(".rgen.spv",
                WavefrontShaderPermutation.suffix(false, false));
        assertEquals(".rgen.spv",
                WavefrontShaderPermutation.suffix(false, true));
        assertEquals(".rgen.spv",
                WavefrontShaderPermutation.suffix(true, false));
        assertEquals("_ser.rgen.spv",
                WavefrontShaderPermutation.suffix(true, true));
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
