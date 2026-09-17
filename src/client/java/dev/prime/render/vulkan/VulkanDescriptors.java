// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Checked creation and binding of Prime's Vulkan descriptor resources. */
public final class VulkanDescriptors {
    private VulkanDescriptors() {
    }

    public static long createPipelineLayout(
            VulkanContext context,
            MemoryStack stack,
            long setLayout,
            VkPushConstantRange.Buffer pushConstants,
            String operation) {
        return createPipelineLayout(
                context, stack, stack.longs(setLayout), pushConstants, operation);
    }

    public static long createPipelineLayout(
            VulkanContext context,
            MemoryStack stack,
            LongBuffer setLayouts,
            VkPushConstantRange.Buffer pushConstants,
            String operation) {
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreatePipelineLayout(
                        context.vkDevice(),
                        VkPipelineLayoutCreateInfo.calloc(stack)
                                .sType$Default()
                                .pSetLayouts(setLayouts)
                                .pPushConstantRanges(pushConstants),
                        null,
                        pointer),
                operation);
        return pointer.get(0);
    }

    public static long createPool(
            VulkanContext context,
            MemoryStack stack,
            int maxSets,
            VkDescriptorPoolSize.Buffer poolSizes,
            String operation) {
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorPool(
                        context.vkDevice(),
                        VkDescriptorPoolCreateInfo.calloc(stack)
                                .sType$Default()
                                .maxSets(maxSets)
                                .pPoolSizes(poolSizes),
                        null,
                        pointer),
                operation);
        return pointer.get(0);
    }

    public static long allocateSet(
            VulkanContext context,
            MemoryStack stack,
            long descriptorPool,
            long setLayout,
            String operation) {
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkAllocateDescriptorSets(
                        context.vkDevice(),
                        VkDescriptorSetAllocateInfo.calloc(stack)
                                .sType$Default()
                                .descriptorPool(descriptorPool)
                                .pSetLayouts(stack.longs(setLayout)),
                        pointer),
                operation);
        return pointer.get(0);
    }

    public static long createSetLayout(
            VulkanContext context,
            MemoryStack stack,
            VkDescriptorSetLayoutBinding.Buffer bindings,
            String operation) {
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(
                        context.vkDevice(),
                        VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                .sType$Default()
                                .pBindings(bindings),
                        null,
                        pointer),
                operation);
        return pointer.get(0);
    }

    public static void layoutBinding(
            VkDescriptorSetLayoutBinding binding,
            int number,
            int type,
            int count,
            int stages) {
        binding.binding(number)
                .descriptorType(type)
                .descriptorCount(count)
                .stageFlags(stages);
    }

    public static void writeImage(
            VkWriteDescriptorSet write,
            long set,
            int binding,
            int type,
            VkDescriptorImageInfo info) {
        writeImages(write, set, binding, type, info, 1);
    }

    public static void writeImages(
            VkWriteDescriptorSet write,
            long set,
            int binding,
            int type,
            VkDescriptorImageInfo first,
            int count) {
        write.sType$Default()
                .dstSet(set)
                .dstBinding(binding)
                .descriptorCount(count)
                .descriptorType(type)
                .pImageInfo(VkDescriptorImageInfo.create(first.address(), count));
    }

    public static void writeBuffer(
            VkWriteDescriptorSet write,
            long set,
            int binding,
            int type,
            VkDescriptorBufferInfo info) {
        write.sType$Default()
                .dstSet(set)
                .dstBinding(binding)
                .descriptorCount(1)
                .descriptorType(type)
                .pBufferInfo(VkDescriptorBufferInfo.create(info.address(), 1));
    }

    public static BoundSet bindStorageImages(
            VulkanContext context,
            MemoryStack stack,
            long setLayout,
            List<VulkanImage> images,
            String label) {
        if (images.isEmpty()) {
            throw new IllegalArgumentException("A storage-image set cannot be empty");
        }
        Binding[] bindings = new Binding[images.size()];
        for (int index = 0; index < images.size(); index++) {
            bindings[index] = image(
                    index,
                    VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    images.get(index).view(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL);
        }
        return bind(context, stack, setLayout, label, bindings);
    }

    public static ImageBinding image(
            int binding, int type, long view, int layout) {
        return new ImageBinding(binding, type, view, layout);
    }

    public static BufferBinding buffer(
            int binding, int type, long buffer, long offset, long range) {
        return new BufferBinding(binding, type, buffer, offset, range);
    }

    public static SampledImageBinding sampledImage(int binding, long view, int layout, long sampler) {
        return new SampledImageBinding(binding, view, layout, sampler);
    }

    /** Allocates, writes and owns one immutable descriptor set and its private pool. */
    public static BoundSet bind(
            VulkanContext context,
            MemoryStack stack,
            long setLayout,
            String label,
            Binding... bindings) {
        if (bindings.length == 0) {
            throw new IllegalArgumentException("A descriptor set cannot be empty");
        }
        Map<Integer, Integer> typeCounts = new LinkedHashMap<>();
        HashSet<Integer> numbers = new HashSet<>();
        int imageCount = 0;
        int bufferCount = 0;
        for (Binding binding : bindings) {
            if (!numbers.add(binding.binding())) {
                throw new IllegalArgumentException("Duplicate descriptor binding");
            }
            typeCounts.merge(binding.type(), 1, Math::addExact);
            if (binding instanceof ImageBinding || binding instanceof SampledImageBinding) {
                imageCount++;
            } else if (binding instanceof BufferBinding) {
                bufferCount++;
            } else {
                throw new IllegalArgumentException("Unknown descriptor binding type");
            }
        }
        long pool = 0L;
        try {
            VkDescriptorPoolSize.Buffer poolSizes =
                    VkDescriptorPoolSize.calloc(typeCounts.size(), stack);
            int poolIndex = 0;
            for (Map.Entry<Integer, Integer> entry : typeCounts.entrySet()) {
                poolSizes.get(poolIndex++)
                        .type(entry.getKey())
                        .descriptorCount(entry.getValue());
            }
            pool = createPool(
                    context, stack, 1, poolSizes, "create " + label + " descriptor pool");
            long set = allocateSet(
                    context, stack, pool, setLayout, "allocate " + label + " descriptor set");
            VkDescriptorImageInfo.Buffer infos =
                    VkDescriptorImageInfo.calloc(imageCount, stack);
            VkDescriptorBufferInfo.Buffer bufferInfos =
                    VkDescriptorBufferInfo.calloc(bufferCount, stack);
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(bindings.length, stack);
            int imageIndex = 0;
            int bufferIndex = 0;
            for (int index = 0; index < bindings.length; index++) {
                Binding binding = bindings[index];
                if (binding instanceof ImageBinding image) {
                    VkDescriptorImageInfo info = infos.get(imageIndex++);
                    info.imageView(image.view())
                            .imageLayout(image.layout());
                    writeImage(writes.get(index), set, image.binding(), image.type(), info);
                } else if (binding instanceof SampledImageBinding image) {
                    VkDescriptorImageInfo info = infos.get(imageIndex++);
                    info.imageView(image.view()).imageLayout(image.layout()).sampler(image.sampler());
                    writeImage(writes.get(index), set, image.binding(), image.type(), info);
                } else if (binding instanceof BufferBinding buffer) {
                    VkDescriptorBufferInfo info = bufferInfos.get(bufferIndex++);
                    info.buffer(buffer.buffer())
                            .offset(buffer.offset())
                            .range(buffer.range());
                    writeBuffer(writes.get(index), set, buffer.binding(), buffer.type(), info);
                }
            }
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new BoundSet(context, pool, set);
        } catch (RuntimeException exception) {
            if (pool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
            }
            throw exception;
        }
    }

    public sealed interface Binding permits ImageBinding, SampledImageBinding, BufferBinding {
        int binding();
        int type();
    }

    public record ImageBinding(
            int binding, int type, long view, int layout) implements Binding {}

    public record SampledImageBinding(int binding, long view, int layout, long sampler) implements Binding {
        @Override public int type() { return VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; }
    }

    public record BufferBinding(
            int binding, int type, long buffer, long offset, long range) implements Binding {}

    public static final class BoundSet implements Destroyable {
        private final VulkanContext context;
        private final long pool;
        private final long set;
        private boolean destroyed;

        BoundSet(VulkanContext context, long pool, long set) {
            this.context = context;
            this.pool = pool;
            this.set = set;
        }

        public long handle() {
            return this.set;
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.pool, null);
            }
        }
    }
}
