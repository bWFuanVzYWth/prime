package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;

final class BasicRawWavefrontFrameTest {
    @Test
    void synchronizationFollowsTheOutputBoundary() {
        long rayTracing =
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        long compute = VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;

        assertEquals(rayTracing, BasicRawWavefrontFrame.destinationStages(false, false));
        assertEquals(
                rayTracing | compute,
                BasicRawWavefrontFrame.destinationStages(true, false));
        assertEquals(compute, BasicRawWavefrontFrame.destinationStages(true, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> BasicRawWavefrontFrame.destinationStages(false, true));
    }
}
