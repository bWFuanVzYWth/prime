package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import java.nio.LongBuffer;
import java.util.List;
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

/** Checked creation of the single-set descriptor layouts used by Prime compute passes. */
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

    public static StorageImageSet bindStorageImages(
            VulkanContext context,
            MemoryStack stack,
            long setLayout,
            List<VulkanImage> images,
            String label) {
        if (images.isEmpty()) {
            throw new IllegalArgumentException("A storage-image set cannot be empty");
        }
        long pool = 0L;
        try {
            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(images.size());
            pool = createPool(
                    context, stack, 1, poolSize, "create " + label + " descriptor pool");
            long set = allocateSet(
                    context, stack, pool, setLayout, "allocate " + label + " descriptor set");
            VkDescriptorImageInfo.Buffer infos =
                    VkDescriptorImageInfo.calloc(images.size(), stack);
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(images.size(), stack);
            for (int binding = 0; binding < images.size(); binding++) {
                infos.get(binding)
                        .imageView(images.get(binding).view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writeImage(
                        writes.get(binding),
                        set,
                        binding,
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                        infos.get(binding));
            }
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new StorageImageSet(context, pool, set);
        } catch (RuntimeException exception) {
            if (pool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
            }
            throw exception;
        }
    }

    public static final class StorageImageSet implements Destroyable {
        private final VulkanContext context;
        private final long pool;
        private final long set;

        private StorageImageSet(VulkanContext context, long pool, long set) {
            this.context = context;
            this.pool = pool;
            this.set = set;
        }

        public long handle() {
            return this.set;
        }

        @Override
        public void destroy() {
            VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.pool, null);
        }
    }
}
