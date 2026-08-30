package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Diagnostic-only image projection. It is absent from every production shader descriptor set. */
public final class ImageDiagnosticPass implements Destroyable {
    public static final int RAW = 0;
    public static final int RADIANCE = 1;
    public static final int NORMAL = 2;
    public static final int ROUGHNESS = 4;
    public static final int DEPTH = 6;
    public static final int MOTION = 7;
    public static final int HIT_R = 8;
    public static final int HIT_A = 9;
    public static final int SH1 = 10;
    public static final int ALBEDO = 11;
    public static final int VISIBILITY_A = 12;
    public static final int SIGNED = 13;

    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int PUSH_SIZE = 40;
    private static final int LOCAL_SIZE = 8;
    private static final int CLEAR = 1;

    private final VulkanContext context;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final long pipelineLayout;
    private final long pipeline;
    private final VulkanImage[] sources;
    private final VulkanImage output;
    private boolean destroyed;

    private ImageDiagnosticPass(
            VulkanContext context,
            long descriptorSetLayout,
            long descriptorPool,
            long[] descriptorSets,
            long pipelineLayout,
            long pipeline,
            VulkanImage[] sources,
            VulkanImage output) {
        this.context = context;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSets = descriptorSets;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.sources = sources;
        this.output = output;
    }

    public static ImageDiagnosticPass createSdr(
            VulkanContext context, VulkanImage output, VulkanImage... sources) {
        return create(
                context,
                output,
                GeneratedShaderPrograms.resource("image_diagnostic_rgba8"),
                sources);
    }

    public static ImageDiagnosticPass createHdr(
            VulkanContext context, VulkanImage output, VulkanImage... sources) {
        return create(
                context,
                output,
                GeneratedShaderPrograms.resource("image_diagnostic_rgba16"),
                sources);
    }

    private static ImageDiagnosticPass create(
            VulkanContext context,
            VulkanImage output,
            String shaderResource,
            VulkanImage... sources) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(output, "output");
        if (sources.length == 0) {
            throw new IllegalArgumentException("Image diagnostics need at least one source");
        }
        VulkanImage[] ownedSources = sources.clone();
        for (VulkanImage source : ownedSources) Objects.requireNonNull(source, "source");
        long setLayout = 0L;
        long descriptorPool = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(1).stageFlags(COMPUTE_STAGE);
            bindings.get(1).binding(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(COMPUTE_STAGE);
            setLayout = VulkanDescriptors.createSetLayout(
                    context, stack, bindings, "create image-diagnostic descriptor layout");
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(COMPUTE_STAGE).offset(0).size(PUSH_SIZE);
            pipelineLayout = VulkanDescriptors.createPipelineLayout(
                    context, stack, setLayout, pushRange,
                    "create image-diagnostic pipeline layout");
            long shader = VulkanShaderModules.create(context, stack, shaderResource);
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default().stage(COMPUTE_STAGE).module(shader).pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
                info.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                LongBuffer pointer = stack.mallocLong(1);
                context.createComputePipeline(info, pointer, "image diagnostics");
                pipeline = pointer.get(0);
            } finally {
                VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
            }
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(ownedSources.length);
            poolSizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(ownedSources.length);
            descriptorPool = VulkanDescriptors.createPool(
                    context, stack, ownedSources.length, poolSizes,
                    "create image-diagnostic descriptor pool");
            LongBuffer layouts = stack.mallocLong(ownedSources.length);
            for (int index = 0; index < ownedSources.length; index++) layouts.put(setLayout);
            layouts.flip();
            LongBuffer pointers = stack.mallocLong(ownedSources.length);
            VulkanContext.check(
                    VK12.vkAllocateDescriptorSets(
                            context.vkDevice(),
                            VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                                    .descriptorPool(descriptorPool).pSetLayouts(layouts),
                            pointers),
                    "allocate image-diagnostic descriptor sets");
            long[] descriptorSets = new long[ownedSources.length];
            VkDescriptorImageInfo.Buffer imageInfos =
                    VkDescriptorImageInfo.calloc(ownedSources.length * 2, stack);
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(ownedSources.length * 2, stack);
            for (int index = 0; index < ownedSources.length; index++) {
                descriptorSets[index] = pointers.get(index);
                imageInfos.get(index * 2).imageView(ownedSources[index].view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(index * 2 + 1).imageView(output.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(index * 2).sType$Default().dstSet(descriptorSets[index]).dstBinding(0)
                        .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(
                                imageInfos.get(index * 2).address(), 1));
                writes.get(index * 2 + 1).sType$Default()
                        .dstSet(descriptorSets[index]).dstBinding(1)
                        .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(
                                imageInfos.get(index * 2 + 1).address(), 1));
            }
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new ImageDiagnosticPass(
                    context, setLayout, descriptorPool, descriptorSets,
                    pipelineLayout, pipeline, ownedSources, output);
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            if (pipeline != 0L) VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
            if (pipelineLayout != 0L) VK12.vkDestroyPipelineLayout(context.vkDevice(), pipelineLayout, null);
            if (setLayout != 0L) VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), setLayout, null);
            throw exception;
        }
    }

    /** Writes every output pixel, including black aspect bars, so no prior diagnostic survives. */
    public void recordFull(VkCommandBuffer commandBuffer, View view) {
        barrier(commandBuffer);
        recordPanel(commandBuffer, view, 0, 0, this.output.width(), this.output.height(), false);
    }

    /** Clears the whole output first, then writes a square n*n grid without stretching sources. */
    public void recordGrid(VkCommandBuffer commandBuffer, int columns, List<View> views) {
        if (columns <= 0 || views.size() > columns * columns) {
            throw new IllegalArgumentException("Invalid image-diagnostic grid");
        }
        barrier(commandBuffer);
        recordPanel(commandBuffer, views.get(0), 0, 0,
                this.output.width(), this.output.height(), true);
        barrier(commandBuffer);
        int cellWidth = this.output.width() / columns;
        int cellHeight = this.output.height() / columns;
        for (int index = 0; index < views.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            int x0 = column * cellWidth;
            int y0 = row * cellHeight;
            int width = column == columns - 1 ? this.output.width() - x0 : cellWidth;
            int height = row == columns - 1 ? this.output.height() - y0 : cellHeight;
            recordPanel(commandBuffer, views.get(index), x0, y0, width, height, false);
        }
    }

    /** Replaces one grid cell without touching its neighbors. */
    public void recordCell(
            VkCommandBuffer commandBuffer, int columns, int index, View view) {
        if (columns <= 0 || index < 0 || index >= columns * columns) {
            throw new IllegalArgumentException("Invalid image-diagnostic cell");
        }
        barrier(commandBuffer);
        int cellWidth = this.output.width() / columns;
        int cellHeight = this.output.height() / columns;
        int column = index % columns;
        int row = index / columns;
        int x0 = column * cellWidth;
        int y0 = row * cellHeight;
        int width = column == columns - 1 ? this.output.width() - x0 : cellWidth;
        int height = row == columns - 1 ? this.output.height() - y0 : cellHeight;
        recordPanel(commandBuffer, view, x0, y0, width, height, false);
    }

    private static void barrier(VkCommandBuffer commandBuffer) {
        VulkanSync.memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private void recordPanel(
            VkCommandBuffer commandBuffer,
            View view,
            int x,
            int y,
            int width,
            int height,
            boolean clear) {
        if (view.source < 0 || view.source >= this.sources.length) {
            throw new IllegalArgumentException("Invalid image-diagnostic source " + view.source);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage source = this.sources[view.source];
            ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putInt(0, source.width());
            push.putInt(4, source.height());
            push.putInt(8, this.output.width());
            push.putInt(12, this.output.height());
            push.putInt(16, x);
            push.putInt(20, y);
            push.putInt(24, width);
            push.putInt(28, height);
            push.putInt(32, view.presentation);
            push.putInt(36, clear ? CLEAR : 0);
            VK12.vkCmdBindPipeline(
                    commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout, 0, stack.longs(this.descriptorSets[view.source]), null);
            VK12.vkCmdPushConstants(
                    commandBuffer, this.pipelineLayout, COMPUTE_STAGE, 0, push);
            VK12.vkCmdDispatch(
                    commandBuffer,
                    DispatchMath.divideRoundUp(width, LOCAL_SIZE),
                    DispatchMath.divideRoundUp(height, LOCAL_SIZE),
                    1);
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
        VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
        VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
    }

    public record View(int source, int presentation) {}
}
