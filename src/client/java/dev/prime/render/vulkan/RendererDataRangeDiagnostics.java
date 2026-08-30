package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Opt-in, asynchronous GPU reduction for renderer-data range decisions. */
public final class RendererDataRangeDiagnostics implements Destroyable {
    public static final String INTERVAL_PROPERTY =
            "prime.renderer.measure.gpuIntervalFrames";
    public static final int DEFAULT_INTERVAL_FRAMES = 120;
    public static final int MAX_INTERVAL_FRAMES = 3_600;

    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int LOCAL_SIZE = 8;
    private static final int PUSH_SIZE = 16;
    private static final int WORDS = 272;
    private static final int BYTES = WORDS * Integer.BYTES;
    private static final int DEPTH_MIN_WORD = 10;
    private static final int MOTION_HISTOGRAM_WORD = 16;
    private static final int DEPTH_HISTOGRAM_WORD = 144;
    private static final int HISTOGRAM_BINS = 128;

    private final VulkanContext context;
    private final int intervalFrames;
    private long frameCount;
    private long nextCaptureFrame = 1L;
    private Pass pass;
    private volatile VulkanBuffer pendingReadback;
    private volatile Snapshot latest;
    private volatile boolean destroyed;

    public RendererDataRangeDiagnostics(VulkanContext context, int intervalFrames) {
        this.context = Objects.requireNonNull(context, "context");
        if (intervalFrames < 1 || intervalFrames > MAX_INTERVAL_FRAMES) {
            throw new IllegalArgumentException(
                    "GPU measurement interval must be in [1, 3600]");
        }
        this.intervalFrames = intervalFrames;
    }

    public Capture record(
            VkCommandBuffer commandBuffer,
            VulkanImage viewZ,
            VulkanImage motion,
            boolean historyReset) {
        requireOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(viewZ, "viewZ");
        Objects.requireNonNull(motion, "motion");
        this.frameCount++;
        if ((!historyReset && this.frameCount < this.nextCaptureFrame)
                || this.pendingReadback != null) {
            return null;
        }
        requireSource(viewZ, "view Z");
        requireSource(motion, "motion");
        if (viewZ.width() != motion.width() || viewZ.height() != motion.height()) {
            throw new IllegalArgumentException(
                    "Renderer-data depth and motion extents differ");
        }
        Pass active = ensurePass(viewZ, motion);
        VulkanBuffer readback = this.context.createReadbackBuffer(
                BYTES,
                VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                "Prime renderer-data range readback");
        this.pendingReadback = readback;
        try {
            VK12.vkCmdFillBuffer(
                    commandBuffer, active.result.handle(), 0L, BYTES, 0);
            VK12.vkCmdFillBuffer(
                    commandBuffer,
                    active.result.handle(),
                    (long) DEPTH_MIN_WORD * Integer.BYTES,
                    Integer.BYTES,
                    Float.floatToRawIntBits(Float.POSITIVE_INFINITY));
            VulkanSync.memoryBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    COMPUTE_STAGE,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            prepareSampledSource(commandBuffer, viewZ);
            prepareSampledSource(commandBuffer, motion);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
                push.putInt(0, viewZ.width());
                push.putInt(4, viewZ.height());
                push.putInt(8, historyReset ? 1 : 0);
                push.putInt(12, 0);
                VK12.vkCmdBindPipeline(
                        commandBuffer,
                        VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                        active.program.pipeline(0));
                VK12.vkCmdBindDescriptorSets(
                        commandBuffer,
                        VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                        active.program.pipelineLayout(),
                        0,
                        stack.longs(active.descriptorSet),
                        null);
                VK12.vkCmdPushConstants(
                        commandBuffer,
                        active.program.pipelineLayout(),
                        COMPUTE_STAGE,
                        0,
                        push);
                VK12.vkCmdDispatch(
                        commandBuffer,
                        DispatchMath.divideRoundUp(viewZ.width(), LOCAL_SIZE),
                        DispatchMath.divideRoundUp(viewZ.height(), LOCAL_SIZE),
                        1);
                VulkanSync.memoryBarrier(
                        commandBuffer,
                        COMPUTE_STAGE,
                        VK12.VK_ACCESS_SHADER_WRITE_BIT,
                        VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_ACCESS_TRANSFER_READ_BIT);
                VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                        .srcOffset(0L)
                        .dstOffset(0L)
                        .size(BYTES);
                VK12.vkCmdCopyBuffer(
                        commandBuffer, active.result.handle(), readback.handle(), copy);
            }
            this.nextCaptureFrame = Math.addExact(this.frameCount, this.intervalFrames);
            return new Capture(
                    this,
                    readback,
                    viewZ.width(),
                    viewZ.height(),
                    historyReset);
        } catch (RuntimeException exception) {
            this.pendingReadback = null;
            ResourceCleanup.destroy(readback, exception);
            throw exception;
        }
    }

    public void submitted(Capture capture) {
        if (capture == null) return;
        capture.require(this);
        try {
            this.context.afterSubmission(() -> complete(capture));
        } catch (RuntimeException exception) {
            if (this.pendingReadback == capture.readback) {
                this.pendingReadback = null;
            }
            ResourceCleanup.run(() -> this.context.defer(capture.readback), exception);
            throw exception;
        }
    }

    public void abandon(Capture capture) {
        if (capture == null) return;
        capture.require(this);
        if (this.pendingReadback == capture.readback) {
            this.pendingReadback = null;
        }
        capture.readback.destroy();
    }

    public Snapshot latest() {
        return this.latest;
    }

    private Pass ensurePass(VulkanImage viewZ, VulkanImage motion) {
        Pass current = this.pass;
        if (current != null && current.matches(viewZ, motion)) {
            return current;
        }
        Pass replacement = Pass.create(this.context, viewZ, motion);
        this.pass = replacement;
        if (current != null) this.context.defer(current);
        return replacement;
    }

    private void complete(Capture capture) {
        if (this.destroyed || this.pendingReadback != capture.readback) {
            return;
        }
        try {
            ByteBuffer words = ByteBuffer.wrap(capture.readback.read(0L, BYTES))
                    .order(ByteOrder.nativeOrder());
            this.latest = Snapshot.merge(this.latest, words, capture);
        } finally {
            this.pendingReadback = null;
            capture.readback.destroy();
        }
    }

    private static void requireSource(VulkanImage image, String name) {
        if ((image.usage() & VK12.VK_IMAGE_USAGE_SAMPLED_BIT) == 0) {
            throw new IllegalArgumentException(
                    "Renderer-data " + name + " image must support sampled reads");
        }
    }

    private static void prepareSampledSource(
            VkCommandBuffer commandBuffer, VulkanImage image) {
        VulkanSync.imageBarrier(
                commandBuffer,
                image.image(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                COMPUTE_STAGE,
                VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("Renderer-data range diagnostics are destroyed");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        VulkanBuffer pending = this.pendingReadback;
        this.pendingReadback = null;
        if (pending != null) pending.destroy();
        Pass current = this.pass;
        this.pass = null;
        if (current != null) current.destroy();
        this.latest = null;
    }

    public record Snapshot(
            long captureCount,
            long resetCaptureCount,
            long sampledPixels,
            long motionFiniteCount,
            long motionNonfiniteCount,
            long motionOutsideUnitCount,
            long motionNonzeroCount,
            float maximumAbsoluteMotionUvX,
            float maximumAbsoluteMotionUvY,
            float maximumMotionPixels,
            long depthSurfaceCount,
            long depthInvalidCount,
            long depthSkyCount,
            float minimumSurfaceViewZ,
            float maximumSurfaceViewZ,
            long[] motionPixelHistogram,
            long[] surfaceViewZHistogram) {
        public Snapshot {
            motionPixelHistogram = motionPixelHistogram.clone();
            surfaceViewZHistogram = surfaceViewZHistogram.clone();
        }

        @Override
        public long[] motionPixelHistogram() {
            return this.motionPixelHistogram.clone();
        }

        @Override
        public long[] surfaceViewZHistogram() {
            return this.surfaceViewZHistogram.clone();
        }

        public double motionPixelsPercentile(double percentile) {
            return percentile(this.motionPixelHistogram, percentile);
        }

        public double surfaceViewZPercentile(double percentile) {
            return percentile(this.surfaceViewZHistogram, percentile);
        }

        static Snapshot merge(Snapshot previous, ByteBuffer words, Capture capture) {
            long[] motionHistogram = previous == null
                    ? new long[HISTOGRAM_BINS]
                    : previous.motionPixelHistogram.clone();
            long[] depthHistogram = previous == null
                    ? new long[HISTOGRAM_BINS]
                    : previous.surfaceViewZHistogram.clone();
            for (int index = 0; index < HISTOGRAM_BINS; index++) {
                motionHistogram[index] += unsigned(
                        words.getInt((MOTION_HISTOGRAM_WORD + index) * Integer.BYTES));
                depthHistogram[index] += unsigned(
                        words.getInt((DEPTH_HISTOGRAM_WORD + index) * Integer.BYTES));
            }
            long depthSurface = unsigned(words.getInt(7 * Integer.BYTES));
            float sampleMinimum = depthSurface == 0L
                    ? Float.POSITIVE_INFINITY
                    : Float.intBitsToFloat(words.getInt(DEPTH_MIN_WORD * Integer.BYTES));
            float previousMinimum = previous == null || previous.depthSurfaceCount == 0L
                    ? Float.POSITIVE_INFINITY
                    : previous.minimumSurfaceViewZ;
            long previousCaptureCount = previous == null ? 0L : previous.captureCount;
            long previousSampledPixels = previous == null ? 0L : previous.sampledPixels;
            return new Snapshot(
                    previousCaptureCount + 1L,
                    (previous == null ? 0L : previous.resetCaptureCount)
                            + (capture.historyReset ? 1L : 0L),
                    previousSampledPixels + (long) capture.width * capture.height,
                    value(previous, Snapshot::motionFiniteCount)
                            + unsigned(words.getInt(0)),
                    value(previous, Snapshot::motionNonfiniteCount)
                            + unsigned(words.getInt(Integer.BYTES)),
                    value(previous, Snapshot::motionOutsideUnitCount)
                            + unsigned(words.getInt(2 * Integer.BYTES)),
                    value(previous, Snapshot::motionNonzeroCount)
                            + unsigned(words.getInt(3 * Integer.BYTES)),
                    Math.max(
                            previous == null ? 0.0F : previous.maximumAbsoluteMotionUvX,
                            Float.intBitsToFloat(words.getInt(4 * Integer.BYTES))),
                    Math.max(
                            previous == null ? 0.0F : previous.maximumAbsoluteMotionUvY,
                            Float.intBitsToFloat(words.getInt(5 * Integer.BYTES))),
                    Math.max(
                            previous == null ? 0.0F : previous.maximumMotionPixels,
                            Float.intBitsToFloat(words.getInt(6 * Integer.BYTES))),
                    value(previous, Snapshot::depthSurfaceCount) + depthSurface,
                    value(previous, Snapshot::depthInvalidCount)
                            + unsigned(words.getInt(8 * Integer.BYTES)),
                    value(previous, Snapshot::depthSkyCount)
                            + unsigned(words.getInt(9 * Integer.BYTES)),
                    Math.min(previousMinimum, sampleMinimum),
                    Math.max(
                            previous == null ? 0.0F : previous.maximumSurfaceViewZ,
                            Float.intBitsToFloat(words.getInt(11 * Integer.BYTES))),
                    motionHistogram,
                    depthHistogram);
        }

        private static long value(
                Snapshot snapshot,
                java.util.function.ToLongFunction<Snapshot> getter) {
            return snapshot == null ? 0L : getter.applyAsLong(snapshot);
        }

        private static long unsigned(int value) {
            return Integer.toUnsignedLong(value);
        }

        private static double percentile(long[] histogram, double percentile) {
            if (!(percentile >= 0.0 && percentile <= 1.0)) {
                throw new IllegalArgumentException("Percentile must be in [0, 1]");
            }
            long total = 0L;
            for (long count : histogram) total = Math.addExact(total, count);
            if (total == 0L) return 0.0;
            long target = Math.max(1L, (long) Math.ceil(percentile * total));
            long cumulative = 0L;
            for (int index = 0; index < histogram.length; index++) {
                cumulative += histogram[index];
                if (cumulative >= target) {
                    return Math.scalb(1.0, -32) * Math.pow(2.0, (index + 1) * 0.5);
                }
            }
            throw new AssertionError("Histogram count changed while reading");
        }
    }

    public static final class Capture {
        private final RendererDataRangeDiagnostics owner;
        private final VulkanBuffer readback;
        private final int width;
        private final int height;
        private final boolean historyReset;

        private Capture(
                RendererDataRangeDiagnostics owner,
                VulkanBuffer readback,
                int width,
                int height,
                boolean historyReset) {
            this.owner = owner;
            this.readback = readback;
            this.width = width;
            this.height = height;
            this.historyReset = historyReset;
        }

        private void require(RendererDataRangeDiagnostics expected) {
            if (this.owner != expected) {
                throw new IllegalArgumentException(
                        "Renderer-data capture belongs to another owner");
            }
        }
    }

    private static final class Pass implements Destroyable {
        private final VulkanContext context;
        private final SharedComputeProgram program;
        private final VulkanImage viewZ;
        private final VulkanImage motion;
        private final VulkanBuffer result;
        private final long descriptorPool;
        private final long descriptorSet;
        private boolean destroyed;

        private Pass(
                VulkanContext context,
                SharedComputeProgram program,
                VulkanImage viewZ,
                VulkanImage motion,
                VulkanBuffer result,
                long descriptorPool,
                long descriptorSet) {
            this.context = context;
            this.program = program;
            this.viewZ = viewZ;
            this.motion = motion;
            this.result = result;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
        }

        static Pass create(
                VulkanContext context, VulkanImage viewZ, VulkanImage motion) {
            SharedComputeProgram program = null;
            VulkanBuffer result = null;
            long descriptorPool = 0L;
            try {
                program = context.acquireRendererDataRangeProgram();
                result = context.createBuffer(
                        BYTES,
                        VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                                | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        false,
                        "Prime renderer-data range result");
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkDescriptorPoolSize.Buffer sizes =
                            VkDescriptorPoolSize.calloc(2, stack);
                    sizes.get(0)
                            .type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                            .descriptorCount(2);
                    sizes.get(1)
                            .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .descriptorCount(1);
                    descriptorPool = VulkanDescriptors.createPool(
                            context,
                            stack,
                            1,
                            sizes,
                            "create renderer-data range descriptor pool");
                    long descriptorSet = VulkanDescriptors.allocateSet(
                            context,
                            stack,
                            descriptorPool,
                            program.descriptorSetLayout(),
                            "allocate renderer-data range descriptor set");
                    VkDescriptorImageInfo.Buffer imageInfos =
                            VkDescriptorImageInfo.calloc(2, stack);
                    imageInfos.get(0)
                            .imageView(viewZ.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    imageInfos.get(1)
                            .imageView(motion.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorBufferInfo.Buffer bufferInfo =
                            VkDescriptorBufferInfo.calloc(1, stack);
                    bufferInfo.get(0)
                            .buffer(result.handle())
                            .offset(0L)
                            .range(BYTES);
                    VkWriteDescriptorSet.Buffer writes =
                            VkWriteDescriptorSet.calloc(3, stack);
                    for (int binding = 0; binding < 2; binding++) {
                        writes.get(binding)
                                .sType$Default()
                                .dstSet(descriptorSet)
                                .dstBinding(binding)
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(
                                        imageInfos.get(binding).address(), 1));
                    }
                    writes.get(2)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(2)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfo.get(0).address(), 1));
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new Pass(
                            context,
                            program,
                            viewZ,
                            motion,
                            result,
                            descriptorPool,
                            descriptorSet);
                }
            } catch (RuntimeException exception) {
                if (descriptorPool != 0L) {
                    VK12.vkDestroyDescriptorPool(
                            context.vkDevice(), descriptorPool, null);
                }
                ResourceCleanup.destroy(result, exception);
                if (program != null) program.release();
                throw exception;
            }
        }

        boolean matches(VulkanImage expectedViewZ, VulkanImage expectedMotion) {
            return this.viewZ == expectedViewZ && this.motion == expectedMotion;
        }

        @Override
        public void destroy() {
            if (this.destroyed) return;
            this.destroyed = true;
            VK12.vkDestroyDescriptorPool(
                    this.context.vkDevice(), this.descriptorPool, null);
            this.result.destroy();
            this.program.release();
        }
    }
}
