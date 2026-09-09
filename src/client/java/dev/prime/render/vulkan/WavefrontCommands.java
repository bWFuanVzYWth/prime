// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;

/** Shared command recording for realtime and offline wavefront queues. */
final class WavefrontCommands {
    static final long COMMAND_WRITE_SOURCE_STAGES =
            KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                    | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
    static final long COMMAND_WRITE_SOURCE_ACCESSES =
            VK12.VK_ACCESS_SHADER_READ_BIT
                    | VK12.VK_ACCESS_SHADER_WRITE_BIT
                    | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT;

    private WavefrontCommands() {
    }

    static void trace(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            TraceProgram program,
            int width,
            int height,
            int group) {
        KHRRayTracingPipeline.vkCmdTraceRaysKHR(
                commandBuffer,
                raygen(stack, program, group),
                miss(stack, program),
                hit(stack, program),
                VkStridedDeviceAddressRegionKHR.calloc(stack),
                width,
                height,
                1);
    }

    static void traceIndirect(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            TraceProgram program,
            VulkanBuffer wavefront,
            int group,
            long commandOffset,
            int commandQueue,
            int commandStride) {
        KHRRayTracingPipeline.vkCmdTraceRaysIndirectKHR(
                commandBuffer,
                raygen(stack, program, group),
                miss(stack, program),
                hit(stack, program),
                VkStridedDeviceAddressRegionKHR.calloc(stack),
                        wavefront.deviceAddress()
                        + commandOffset
                        + (long) commandQueue * commandStride);
    }

    static void initializeQueues(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanBuffer wavefront,
            long commandOffset,
            int queueCount,
            int commandStride) {
        initializeQueues(
                commandBuffer, stack, wavefront, commandOffset,
                queueCount, commandStride, 0);
    }

    static void initializeQueues(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanBuffer wavefront,
            long commandOffset,
            int queueCount,
            int commandStride,
            int queueZeroMetadata) {
        long commandBytes = (long) queueCount * commandStride;
        wavefrontToTransferBarrier(
                commandBuffer, stack, wavefront, commandOffset, commandBytes);
        ByteBuffer commands = stack.calloc(queueCount * commandStride);
        initializeQueueCommands(commands, queueCount, commandStride, queueZeroMetadata);
        VK12.vkCmdUpdateBuffer(commandBuffer, wavefront.handle(), commandOffset, commands);
        transferToWavefrontBarrier(
                commandBuffer, stack, wavefront, commandOffset, commandBytes);
    }

    static void initializeQueueCommands(
            ByteBuffer commands,
            int queueCount,
            int commandStride,
            int queueZeroMetadata) {
        if (queueCount <= 0 || commandStride < 4 * Integer.BYTES
                || commands.remaining() < queueCount * commandStride) {
            throw new IllegalArgumentException("Invalid wavefront indirect-command layout");
        }
        for (int queue = 0; queue < queueCount; queue++) {
            int offset = queue * commandStride;
            commands.putInt(offset + Integer.BYTES, 1);
            commands.putInt(offset + 2 * Integer.BYTES, 1);
        }
        // VkTraceRaysIndirectCommandKHR consumes only the first three uints. Queue zero owns the
        // ABI-defined fourth word; shader-side atomic count resets touch only word zero.
        commands.putInt(3 * Integer.BYTES, queueZeroMetadata);
    }

    static void wavefrontBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanBuffer wavefront) {
        VulkanSync.bufferBarrier(
                commandBuffer,
                stack,
                wavefront,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT
                        | VK12.VK_ACCESS_SHADER_WRITE_BIT
                        | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
    }

    private static VkStridedDeviceAddressRegionKHR raygen(
            MemoryStack stack, TraceProgram program, int group) {
        return VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(program.raygenAddress(group))
                .stride(program.raygenRecordStride)
                .size(program.raygenRecordStride);
    }

    private static VkStridedDeviceAddressRegionKHR miss(
            MemoryStack stack, TraceProgram program) {
        return VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(program.missAddress)
                .stride(program.recordStride)
                .size(program.recordStride * TraceProgram.MISS_GROUP_COUNT);
    }

    private static VkStridedDeviceAddressRegionKHR hit(
            MemoryStack stack, TraceProgram program) {
        return VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(program.hitAddress)
                .stride(program.recordStride)
                .size(program.recordStride * TraceProgram.HIT_GROUP_COUNT);
    }

    private static void wavefrontToTransferBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanBuffer wavefront,
            long offset,
            long size) {
        VulkanSync.bufferBarrier(
                commandBuffer,
                stack,
                wavefront.handle(),
                offset,
                size,
                COMMAND_WRITE_SOURCE_STAGES,
                COMMAND_WRITE_SOURCE_ACCESSES,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
    }

    private static void transferToWavefrontBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanBuffer wavefront,
            long offset,
            long size) {
        VulkanSync.bufferBarrier(
                commandBuffer,
                stack,
                wavefront.handle(),
                offset,
                size,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT
                        | VK12.VK_ACCESS_SHADER_WRITE_BIT
                        | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
    }
}
