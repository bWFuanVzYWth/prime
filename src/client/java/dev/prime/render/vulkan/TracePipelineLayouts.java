package dev.prime.render.vulkan;

import dev.prime.render.shader.ShaderAbi;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VkPushConstantRange;

/** Shared pipeline-layout ABI for realtime and offline ray tracing. */
final class TracePipelineLayouts {
    static final int ALL_RT_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

    private TracePipelineLayouts() {
    }

    static long create(
            VulkanContext context,
            MemoryStack stack,
            long sharedSetLayout,
            long integratorSetLayout,
            String label) {
        VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
        range.get(0)
                .stageFlags(ALL_RT_STAGES)
                .offset(0)
                .size(ShaderAbi.PUSH_CONSTANT_SIZE);
        return VulkanDescriptors.createPipelineLayout(
                context,
                stack,
                stack.longs(sharedSetLayout, integratorSetLayout),
                range,
                "create " + label + " trace pipeline layout");
    }
}
