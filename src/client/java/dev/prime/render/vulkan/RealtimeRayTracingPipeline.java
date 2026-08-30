package dev.prime.render.vulkan;

import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.shader.ShaderAbi;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Standard realtime renderer. */
public final class RealtimeRayTracingPipeline extends RealtimeRayTracingPipelineSupport {
    static final int RAYGEN_GROUP_COUNT = RealtimeStandardGroups.GROUP_COUNT;
    static final int RAYGEN_MODULE_COUNT = RealtimeStandardGroups.MODULE_COUNT;

    static int dispatchCount(int minimumBounces) {
        dev.prime.render.MinimumBounceSettings.validateCount(
                minimumBounces);
        // Landing owns the primary-surface bounce. Every additional minimum bounce has four
        // narrow stages; admission and the register tail replace all remaining dispatches.
        return 4 * (minimumBounces - 1) + 14;
    }

    static int[] primaryDirectInputImageIndices() {
        return new int[] {1, 2};
    }

    static int[] primaryInputImageIndices() {
        return new int[] {0, 1, 2, 4, 6, 7, 8, 9, 10, 20, 21};
    }

    static int[] nextStepInputImageIndices() {
        // Guide images participate even when the next stage only writes them: the fallback and
        // first-owned guide stores require an explicit WAW dependency. Omitting those images lets
        // an earlier fallback write win after the ownership transition.
        return new int[] {
            0, 1, 2, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 21
        };
    }

    static boolean standardBarrierPublishesImagesBefore(int group) {
        return switch (group) {
            case RealtimePrimaryGroups.DELTA_WALK_0,
                    RealtimePrimaryGroups.GUIDE_DELTA_WALK_0,
                    RealtimePrimaryGroups.DELTA_WALK_1,
                    RealtimePrimaryGroups.GUIDE_DELTA_WALK_1,
                    RealtimePrimaryGroups.LANDING_DIRECT,
                    RealtimePrimaryGroups.LANDING_SCATTER,
                    RealtimeStandardGroups.DIRECT_0,
                    RealtimeStandardGroups.DIRECT_1,
                    RealtimeStandardGroups.SCATTER_0,
                    RealtimeStandardGroups.SCATTER_1 -> true;
            default -> false;
        };
    }

    public RealtimeRayTracingPipeline(VulkanContext context, TraceBackend backend) {
        super(
                context,
                backend,
                RealtimeStandardGroups.standardSchedule(
                        context.capabilities().wavefrontShaderSuffix()),
                dispatchCount(dev.prime.render.MinimumBounceSettings.MAXIMUM_COUNT),
                "Prime realtime ray tracing pipeline",
                "Prime realtime shader binding table");
    }

    @Override
    protected int recordTransport(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            TraceProgram activeProgram,
            IntegratorFrameInput input,
            long commandOffset) {
        this.traceDirect(
                commandBuffer,
                stack,
                activeProgram,
                input.width(),
                input.height(),
                RealtimePrimaryGroups.CAMERA_TRACE);
        this.recordPrimaryPrefix(commandBuffer, stack, activeProgram, commandOffset);
        int minimumBounces = input.minimumBounces();
        boolean sourceOne = false;
        this.queueBarrier(commandBuffer, stack);
        for (int round = 1; round < minimumBounces; round++) {
            int sourceQueue = sourceOne
                    ? ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
                    : ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0;
            this.traceQueued(
                    commandBuffer,
                    stack,
                    activeProgram,
                    RealtimeStandardGroups.bridgeTrace(sourceOne),
                    commandOffset,
                    sourceQueue);
            this.queueBarrier(commandBuffer, stack);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    activeProgram,
                    RealtimeStandardGroups.lightSelect(sourceOne),
                    commandOffset,
                    sourceQueue);
            this.nextStepBarrier(commandBuffer, stack);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    activeProgram,
                    RealtimeStandardGroups.direct(sourceOne),
                    commandOffset,
                    sourceQueue);
            this.nextStepBarrier(commandBuffer, stack);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    activeProgram,
                    RealtimeStandardGroups.scatter(sourceOne),
                    commandOffset,
                    sourceQueue);
            this.queueBarrier(commandBuffer, stack);
            sourceOne = !sourceOne;
        }
        int tailSourceQueue = sourceOne
                ? ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
                : ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0;
        this.traceQueued(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeStandardGroups.tailAdmission(sourceOne),
                commandOffset,
                tailSourceQueue);
        this.nextStepBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeStandardGroups.TAIL,
                commandOffset,
                ShaderAbi.WAVEFRONT_AREA_QUEUE);
        this.resolveInputBarrier(commandBuffer, stack);
        this.recordOutputTail(
                commandBuffer,
                stack,
                activeProgram,
                commandOffset,
                input.width(),
                input.height(),
                RealtimeStandardGroups.BRANCH_RESOLVE,
                RealtimeStandardGroups.NOISY_OUTPUT_RESOLVE);
        return dispatchCount(minimumBounces);
    }

    private void standardBarrierBefore(
            VkCommandBuffer commandBuffer, MemoryStack stack, int group) {
        if (standardBarrierPublishesImagesBefore(group)) {
            this.nextStepBarrier(commandBuffer, stack);
        } else {
            this.queueBarrier(commandBuffer, stack);
        }
    }

}
