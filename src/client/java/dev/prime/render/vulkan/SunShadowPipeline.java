// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;

/** Shared sun-cache raygen, independent of either integrator pipeline. */
public final class SunShadowPipeline implements Destroyable {
    private final TraceBindings bindings;
    private final TraceProgram program;
    private boolean destroyed;

    public SunShadowPipeline(VulkanContext context, TraceBindings bindings) {
        this.bindings = java.util.Objects.requireNonNull(bindings, "bindings");
        this.program = TraceProgram.create(
                context,
                RaygenSchedule.single(GeneratedShaderPrograms.resource("sun_shadow"), 0),
                "Prime sun-shadow ray tracing pipeline",
                "Prime sun-shadow shader binding table",
                bindings.descriptorSetLayout());
    }

    void trace(
            VkCommandBuffer commandBuffer,
            ByteBuffer pushConstants,
            int width,
            int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Sun-shadow trace extent must be positive");
        }
        if (!this.bindings.ready()) {
            throw new IllegalStateException("Trace-backend resources are not prepared");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.program.bind(
                    commandBuffer,
                    stack,
                    pushConstants,
                    this.bindings.descriptorSet());
            VkStridedDeviceAddressRegionKHR raygen =
                    VkStridedDeviceAddressRegionKHR.calloc(stack)
                            .deviceAddress(this.program.raygenAddress(0))
                            .stride(this.program.raygenRecordStride)
                            .size(this.program.raygenRecordStride);
            VkStridedDeviceAddressRegionKHR miss =
                    VkStridedDeviceAddressRegionKHR.calloc(stack)
                            .deviceAddress(this.program.missAddress)
                            .stride(this.program.recordStride)
                            .size(this.program.recordStride * TraceProgram.MISS_GROUP_COUNT);
            VkStridedDeviceAddressRegionKHR hit =
                    VkStridedDeviceAddressRegionKHR.calloc(stack)
                            .deviceAddress(this.program.hitAddress)
                            .stride(this.program.recordStride)
                            .size(this.program.recordStride * TraceProgram.HIT_GROUP_COUNT);
            KHRRayTracingPipeline.vkCmdTraceRaysKHR(
                    commandBuffer,
                    raygen,
                    miss,
                    hit,
                    VkStridedDeviceAddressRegionKHR.calloc(stack),
                    width,
                    height,
                    1);
        }
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            this.program.destroy();
        }
    }
}
