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

/** Opt-in, asynchronous reduction over raw reconstruction signals before backend adaptation. */
public final class RendererSignalRangeDiagnostics implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int LOCAL_SIZE = 8;
    private static final int PUSH_SIZE = 8;
    private static final int WORDS = 352;
    private static final int BYTES = WORDS * Integer.BYTES;
    private static final int ROUGHNESS_MIN_WORD = 9;
    private static final int HIT_MIN_WORD = 18;
    private static final int RADIANCE_HISTOGRAM_WORD = 32;
    private static final int ROUGHNESS_HISTOGRAM_WORD = 160;
    private static final int HIT_HISTOGRAM_WORD = 224;
    private static final int LOG_HISTOGRAM_BINS = 128;
    private static final int ROUGHNESS_HISTOGRAM_BINS = 64;

    private final VulkanContext context;
    private final int intervalFrames;
    private long frameCount;
    private long nextCaptureFrame = 1L;
    private Pass pass;
    private volatile VulkanBuffer pendingReadback;
    private volatile Snapshot latest;
    private volatile boolean destroyed;

    public RendererSignalRangeDiagnostics(VulkanContext context, int intervalFrames) {
        this.context = Objects.requireNonNull(context, "context");
        if (intervalFrames < 1
                || intervalFrames > RendererDataRangeDiagnostics.MAX_INTERVAL_FRAMES) {
            throw new IllegalArgumentException(
                    "GPU measurement interval must be in [1, 3600]");
        }
        this.intervalFrames = intervalFrames;
    }

    public Capture record(VkCommandBuffer commandBuffer, RawWavefrontFrame frame) {
        requireOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(frame, "frame");
        this.frameCount++;
        if (this.frameCount < this.nextCaptureFrame || this.pendingReadback != null) {
            return null;
        }
        VulkanImage[] images = sources(frame);
        requireSources(images);
        Pass active = ensurePass(images);
        VulkanBuffer readback = this.context.createReadbackBuffer(
                BYTES,
                VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                "Prime renderer signal range readback");
        this.pendingReadback = readback;
        try {
            VK12.vkCmdFillBuffer(commandBuffer, active.result.handle(), 0L, BYTES, 0);
            initializeMinimum(commandBuffer, active.result, ROUGHNESS_MIN_WORD);
            initializeMinimum(commandBuffer, active.result, HIT_MIN_WORD);
            VulkanSync.memoryBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    COMPUTE_STAGE,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            for (VulkanImage image : images) prepareSampledSource(commandBuffer, image);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
                push.putInt(0, images[0].width());
                push.putInt(4, images[0].height());
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
                        DispatchMath.divideRoundUp(images[0].width(), LOCAL_SIZE),
                        DispatchMath.divideRoundUp(images[0].height(), LOCAL_SIZE),
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
            return new Capture(this, readback, images[0].width(), images[0].height());
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
            if (this.pendingReadback == capture.readback) this.pendingReadback = null;
            ResourceCleanup.run(() -> this.context.defer(capture.readback), exception);
            throw exception;
        }
    }

    public void abandon(Capture capture) {
        if (capture == null) return;
        capture.require(this);
        if (this.pendingReadback == capture.readback) this.pendingReadback = null;
        capture.readback.destroy();
    }

    public Snapshot latest() {
        return this.latest;
    }

    private Pass ensurePass(VulkanImage[] images) {
        Pass current = this.pass;
        if (current != null && current.matches(images)) return current;
        Pass replacement = Pass.create(this.context, images);
        this.pass = replacement;
        if (current != null) this.context.defer(current);
        return replacement;
    }

    private void complete(Capture capture) {
        if (this.destroyed || this.pendingReadback != capture.readback) return;
        try {
            ByteBuffer words = ByteBuffer.wrap(capture.readback.read(0L, BYTES))
                    .order(ByteOrder.nativeOrder());
            this.latest = Snapshot.merge(this.latest, words, capture);
        } finally {
            this.pendingReadback = null;
            capture.readback.destroy();
        }
    }

    private static VulkanImage[] sources(RawWavefrontFrame frame) {
        return new VulkanImage[] {
            frame.noisyDiffuse(),
            frame.noisySpecular(),
            frame.normalRoughness(),
            frame.material(),
            frame.specularMaterial(),
            frame.reconstructionControl()
        };
    }

    private static void requireSources(VulkanImage[] images) {
        int width = images[0].width();
        int height = images[0].height();
        for (VulkanImage image : images) {
            if (image.width() != width || image.height() != height) {
                throw new IllegalArgumentException(
                        "Renderer signal measurement extents differ");
            }
            if ((image.usage() & VK12.VK_IMAGE_USAGE_SAMPLED_BIT) == 0) {
                throw new IllegalArgumentException(
                        "Renderer signal measurement source must support sampled reads");
            }
        }
    }

    private static void initializeMinimum(
            VkCommandBuffer commandBuffer, VulkanBuffer result, int word) {
        VK12.vkCmdFillBuffer(
                commandBuffer,
                result.handle(),
                (long) word * Integer.BYTES,
                Integer.BYTES,
                Float.floatToRawIntBits(Float.POSITIVE_INFINITY));
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
            throw new IllegalStateException("Renderer signal diagnostics are destroyed");
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
            long sampledPixels,
            long radianceFiniteCount,
            long radianceNonfiniteCount,
            long radianceNegativeCount,
            long radianceSaturatedCount,
            float maximumRadiance,
            long surfaceCount,
            long normalNonfiniteCount,
            float maximumNormalLengthError,
            long roughnessInvalidCount,
            float minimumRoughness,
            float maximumRoughness,
            long roughnessZeroCount,
            long albedoInvalidCount,
            float maximumAlbedo,
            long hitDistanceFiniteCount,
            long hitDistanceInvalidCount,
            long hitDistanceZeroCount,
            long hitDistanceSaturatedCount,
            float minimumPositiveHitDistance,
            float maximumHitDistance,
            long[] radianceHistogram,
            long[] roughnessHistogram,
            long[] hitDistanceHistogram) {
        public Snapshot {
            radianceHistogram = radianceHistogram.clone();
            roughnessHistogram = roughnessHistogram.clone();
            hitDistanceHistogram = hitDistanceHistogram.clone();
        }

        @Override public long[] radianceHistogram() { return this.radianceHistogram.clone(); }
        @Override public long[] roughnessHistogram() { return this.roughnessHistogram.clone(); }
        @Override public long[] hitDistanceHistogram() {
            return this.hitDistanceHistogram.clone();
        }

        public double radiancePercentile(double percentile) {
            return logPercentile(this.radianceHistogram, percentile);
        }

        public double roughnessPercentile(double percentile) {
            return linearPercentile(this.roughnessHistogram, percentile);
        }

        public double hitDistancePercentile(double percentile) {
            return logPercentile(this.hitDistanceHistogram, percentile);
        }

        static Snapshot merge(Snapshot previous, ByteBuffer words, Capture capture) {
            long[] radiance = histogram(
                    previous == null ? null : previous.radianceHistogram,
                    words,
                    RADIANCE_HISTOGRAM_WORD,
                    LOG_HISTOGRAM_BINS);
            long[] roughness = histogram(
                    previous == null ? null : previous.roughnessHistogram,
                    words,
                    ROUGHNESS_HISTOGRAM_WORD,
                    ROUGHNESS_HISTOGRAM_BINS);
            long[] hit = histogram(
                    previous == null ? null : previous.hitDistanceHistogram,
                    words,
                    HIT_HISTOGRAM_WORD,
                    LOG_HISTOGRAM_BINS);
            long surface = unsigned(words.getInt(5 * Integer.BYTES));
            long validRoughness = surface - unsigned(words.getInt(8 * Integer.BYTES));
            long positiveHit = unsigned(words.getInt(14 * Integer.BYTES))
                    - unsigned(words.getInt(16 * Integer.BYTES));
            return new Snapshot(
                    count(previous, Snapshot::captureCount) + 1L,
                    count(previous, Snapshot::sampledPixels)
                            + (long) capture.width * capture.height,
                    count(previous, Snapshot::radianceFiniteCount) + unsigned(words.getInt(0)),
                    count(previous, Snapshot::radianceNonfiniteCount) + unsigned(words.getInt(4)),
                    count(previous, Snapshot::radianceNegativeCount) + unsigned(words.getInt(8)),
                    count(previous, Snapshot::radianceSaturatedCount) + unsigned(words.getInt(12)),
                    maximum(previous == null ? 0.0F : previous.maximumRadiance, words, 4),
                    count(previous, Snapshot::surfaceCount) + surface,
                    count(previous, Snapshot::normalNonfiniteCount) + unsigned(words.getInt(24)),
                    maximum(previous == null ? 0.0F : previous.maximumNormalLengthError, words, 7),
                    count(previous, Snapshot::roughnessInvalidCount) + unsigned(words.getInt(32)),
                    minimum(
                            previous == null || previous.surfaceCount == previous.roughnessInvalidCount
                                    ? Float.POSITIVE_INFINITY
                                    : previous.minimumRoughness,
                            validRoughness,
                            words,
                            ROUGHNESS_MIN_WORD),
                    maximum(previous == null ? 0.0F : previous.maximumRoughness, words, 10),
                    count(previous, Snapshot::roughnessZeroCount) + unsigned(words.getInt(44)),
                    count(previous, Snapshot::albedoInvalidCount) + unsigned(words.getInt(48)),
                    maximum(previous == null ? 0.0F : previous.maximumAlbedo, words, 13),
                    count(previous, Snapshot::hitDistanceFiniteCount) + unsigned(words.getInt(56)),
                    count(previous, Snapshot::hitDistanceInvalidCount) + unsigned(words.getInt(60)),
                    count(previous, Snapshot::hitDistanceZeroCount) + unsigned(words.getInt(64)),
                    count(previous, Snapshot::hitDistanceSaturatedCount) + unsigned(words.getInt(68)),
                    minimum(
                            previous == null
                                            || previous.hitDistanceFiniteCount
                                                    == previous.hitDistanceZeroCount
                                    ? Float.POSITIVE_INFINITY
                                    : previous.minimumPositiveHitDistance,
                            positiveHit,
                            words,
                            HIT_MIN_WORD),
                    maximum(previous == null ? 0.0F : previous.maximumHitDistance, words, 19),
                    radiance,
                    roughness,
                    hit);
        }

        private static long[] histogram(
                long[] previous, ByteBuffer words, int firstWord, int bins) {
            long[] result = previous == null ? new long[bins] : previous.clone();
            for (int index = 0; index < bins; index++) {
                result[index] += unsigned(words.getInt(
                        (firstWord + index) * Integer.BYTES));
            }
            return result;
        }

        private static float minimum(
                float previous, long sampleCount, ByteBuffer words, int word) {
            if (sampleCount == 0L) return previous == Float.POSITIVE_INFINITY ? 0.0F : previous;
            return Math.min(previous, Float.intBitsToFloat(words.getInt(word * Integer.BYTES)));
        }

        private static float maximum(float previous, ByteBuffer words, int word) {
            return Math.max(
                    previous,
                    Float.intBitsToFloat(words.getInt(word * Integer.BYTES)));
        }

        private static long count(
                Snapshot snapshot, java.util.function.ToLongFunction<Snapshot> getter) {
            return snapshot == null ? 0L : getter.applyAsLong(snapshot);
        }

        private static long unsigned(int value) {
            return Integer.toUnsignedLong(value);
        }

        private static double logPercentile(long[] histogram, double percentile) {
            int index = percentileIndex(histogram, percentile);
            return index < 0
                    ? 0.0
                    : Math.scalb(1.0, -32) * Math.pow(2.0, (index + 1) * 0.5);
        }

        private static double linearPercentile(long[] histogram, double percentile) {
            int index = percentileIndex(histogram, percentile);
            return index < 0 ? 0.0 : (index + 1.0) / histogram.length;
        }

        private static int percentileIndex(long[] histogram, double percentile) {
            if (!(percentile >= 0.0 && percentile <= 1.0)) {
                throw new IllegalArgumentException("Percentile must be in [0, 1]");
            }
            long total = 0L;
            for (long count : histogram) total = Math.addExact(total, count);
            if (total == 0L) return -1;
            long target = Math.max(1L, (long) Math.ceil(percentile * total));
            long cumulative = 0L;
            for (int index = 0; index < histogram.length; index++) {
                cumulative += histogram[index];
                if (cumulative >= target) return index;
            }
            throw new AssertionError("Histogram count changed while reading");
        }
    }

    public static final class Capture {
        private final RendererSignalRangeDiagnostics owner;
        private final VulkanBuffer readback;
        private final int width;
        private final int height;

        private Capture(
                RendererSignalRangeDiagnostics owner,
                VulkanBuffer readback,
                int width,
                int height) {
            this.owner = owner;
            this.readback = readback;
            this.width = width;
            this.height = height;
        }

        private void require(RendererSignalRangeDiagnostics expected) {
            if (this.owner != expected) {
                throw new IllegalArgumentException(
                        "Renderer signal capture belongs to another owner");
            }
        }
    }

    private static final class Pass implements Destroyable {
        private final VulkanContext context;
        private final SharedComputeProgram program;
        private final VulkanImage[] images;
        private final VulkanBuffer result;
        private final long descriptorPool;
        private final long descriptorSet;
        private boolean destroyed;

        private Pass(
                VulkanContext context,
                SharedComputeProgram program,
                VulkanImage[] images,
                VulkanBuffer result,
                long descriptorPool,
                long descriptorSet) {
            this.context = context;
            this.program = program;
            this.images = images.clone();
            this.result = result;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
        }

        static Pass create(VulkanContext context, VulkanImage[] images) {
            SharedComputeProgram program = null;
            VulkanBuffer result = null;
            long descriptorPool = 0L;
            try {
                program = context.acquireRendererSignalRangeProgram();
                result = context.createBuffer(
                        BYTES,
                        VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                                | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        false,
                        "Prime renderer signal range result");
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
                    sizes.get(0)
                            .type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                            .descriptorCount(images.length);
                    sizes.get(1)
                            .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .descriptorCount(1);
                    descriptorPool = VulkanDescriptors.createPool(
                            context,
                            stack,
                            1,
                            sizes,
                            "create renderer signal range descriptor pool");
                    long descriptorSet = VulkanDescriptors.allocateSet(
                            context,
                            stack,
                            descriptorPool,
                            program.descriptorSetLayout(),
                            "allocate renderer signal range descriptor set");
                    VkDescriptorImageInfo.Buffer imageInfos =
                            VkDescriptorImageInfo.calloc(images.length, stack);
                    VkWriteDescriptorSet.Buffer writes =
                            VkWriteDescriptorSet.calloc(images.length + 1, stack);
                    for (int binding = 0; binding < images.length; binding++) {
                        imageInfos.get(binding)
                                .imageView(images[binding].view())
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                        writes.get(binding)
                                .sType$Default()
                                .dstSet(descriptorSet)
                                .dstBinding(binding)
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(
                                        imageInfos.get(binding).address(), 1));
                    }
                    VkDescriptorBufferInfo.Buffer bufferInfo =
                            VkDescriptorBufferInfo.calloc(1, stack);
                    bufferInfo.get(0)
                            .buffer(result.handle())
                            .offset(0L)
                            .range(BYTES);
                    writes.get(images.length)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(images.length)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfo.get(0).address(), 1));
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new Pass(
                            context,
                            program,
                            images,
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

        boolean matches(VulkanImage[] expected) {
            return java.util.Arrays.equals(this.images, expected);
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
