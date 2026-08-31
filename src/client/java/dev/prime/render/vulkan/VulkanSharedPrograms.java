package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

/** Size-independent programs shared by extent-scoped descriptor and image resources. */
final class VulkanSharedPrograms implements AutoCloseable {
    private static final int SAMPLED_IMAGE = VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
    private static final int STORAGE_IMAGE = VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    private static final int STORAGE_BUFFER = VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;

    private final VulkanContext context;
    private SharedComputeProgram displayTransform;
    private SharedComputeProgram hdrPresent;
    private SharedComputeProgram autoExposure;
    private SharedComputeProgram uiAlphaClear;
    private SharedComputeProgram uiAlphaExtract;
    private SharedComputeProgram streamlineInput;
    private SharedComputeProgram rendererDataRange;
    private boolean closed;

    VulkanSharedPrograms(VulkanContext context) {
        this.context = context;
    }

    void prewarm() {
        acquireDisplayTransform().release();
        acquireAutoExposure().release();
        acquireHdrPresent().release();
        acquireUiAlphaClear().release();
        acquireUiAlphaExtract().release();
    }

    SharedComputeProgram acquireDisplayTransform() {
        requireOpen();
        if (this.displayTransform == null) {
            this.displayTransform =
                SharedComputeProgram.create(this.context, "common display-transform", 28,
                    new int[] {SAMPLED_IMAGE, STORAGE_IMAGE, STORAGE_BUFFER, STORAGE_IMAGE},
                    new String[] {GeneratedShaderPrograms.resource("fsr_display")});
        }
        return this.displayTransform.retain();
    }

    SharedComputeProgram acquireAutoExposure() {
        requireOpen();
        if (this.autoExposure == null) {
            this.autoExposure = SharedComputeProgram.create(this.context, "auto-exposure", 16,
                new int[] {
                    SAMPLED_IMAGE, STORAGE_IMAGE, STORAGE_IMAGE, STORAGE_BUFFER, STORAGE_BUFFER},
                new String[] {GeneratedShaderPrograms.resource("auto_exposure_histogram"),
                    GeneratedShaderPrograms.resource("auto_exposure_update")});
        }
        return this.autoExposure.retain();
    }

    SharedComputeProgram acquireHdrPresent() {
        requireOpen();
        if (this.hdrPresent == null) {
            this.hdrPresent = SharedComputeProgram.create(
                    this.context,
                    "HDR presentation",
                    16,
                    new int[] {SAMPLED_IMAGE, SAMPLED_IMAGE, SAMPLED_IMAGE, STORAGE_IMAGE},
                    new String[] {GeneratedShaderPrograms.resource("hdr_present")});
        }
        return this.hdrPresent.retain();
    }

    SharedComputeProgram acquireUiAlphaClear() {
        requireOpen();
        if (this.uiAlphaClear == null) {
            this.uiAlphaClear = SharedComputeProgram.create(
                    this.context,
                    "UI alpha clear",
                    8,
                    new int[] {STORAGE_IMAGE},
                    new String[] {GeneratedShaderPrograms.resource("ui_alpha_clear")});
        }
        return this.uiAlphaClear.retain();
    }

    SharedComputeProgram acquireUiAlphaExtract() {
        requireOpen();
        if (this.uiAlphaExtract == null) {
            this.uiAlphaExtract = SharedComputeProgram.create(
                    this.context,
                    "UI alpha extraction",
                    8,
                    new int[] {SAMPLED_IMAGE, STORAGE_IMAGE},
                    new String[] {GeneratedShaderPrograms.resource("ui_alpha_extract")});
        }
        return this.uiAlphaExtract.retain();
    }

    SharedComputeProgram acquireStreamlineInput() {
        requireOpen();
        if (this.streamlineInput == null) {
            this.streamlineInput = SharedComputeProgram.create(
                    this.context,
                    "Streamline input preparation",
                    dev.prime.render.shader.ShaderAbi.NRD_MOTION_PUSH_CONSTANT_SIZE,
                    new int[] {
                        SAMPLED_IMAGE,
                        SAMPLED_IMAGE,
                        STORAGE_IMAGE,
                        STORAGE_IMAGE,
                        STORAGE_IMAGE
                    },
                    new String[] {GeneratedShaderPrograms.resource("streamline_input")});
        }
        return this.streamlineInput.retain();
    }

    SharedComputeProgram acquireRendererDataRange() {
        requireOpen();
        if (this.rendererDataRange == null) {
            this.rendererDataRange = SharedComputeProgram.create(
                    this.context,
                    "renderer-data range measurement",
                    16,
                    new int[] {SAMPLED_IMAGE, SAMPLED_IMAGE, STORAGE_BUFFER},
                    new String[] {GeneratedShaderPrograms.resource("renderer_data_range")});
        }
        return this.rendererDataRange.retain();
    }

    void invalidate() {
        requireOpen();
        if (this.displayTransform != null) {
            this.displayTransform.release();
            this.displayTransform = null;
        }
        if (this.autoExposure != null) {
            this.autoExposure.release();
            this.autoExposure = null;
        }
        if (this.hdrPresent != null) {
            this.hdrPresent.release();
            this.hdrPresent = null;
        }
        if (this.uiAlphaClear != null) {
            this.uiAlphaClear.release();
            this.uiAlphaClear = null;
        }
        if (this.uiAlphaExtract != null) {
            this.uiAlphaExtract.release();
            this.uiAlphaExtract = null;
        }
        if (this.streamlineInput != null) {
            this.streamlineInput.release();
            this.streamlineInput = null;
        }
        if (this.rendererDataRange != null) {
            this.rendererDataRange.release();
            this.rendererDataRange = null;
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        invalidate();
        this.closed = true;
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Vulkan shared programs are closed");
        }
    }

    static final class SharedComputeProgram implements Destroyable {
        private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;

        private final VulkanContext context;
        private final long descriptorSetLayout;
        private final long pipelineLayout;
        private final long[] pipelines;
        // One reference belongs to VulkanSharedPrograms; each extent-scoped pass retains another.
        // Access is confined to the render thread, so this counter intentionally has no lock.
        private int references = 1;
        private boolean destroyed;

        private SharedComputeProgram(VulkanContext context, long descriptorSetLayout,
            long pipelineLayout, long[] pipelines) {
            this.context = context;
            this.descriptorSetLayout = descriptorSetLayout;
            this.pipelineLayout = pipelineLayout;
            this.pipelines = pipelines;
        }

        static SharedComputeProgram create(VulkanContext context, String label, int pushSize,
            int[] descriptorTypes, String[] shaderResources) {
            if (pushSize < 0 || (pushSize & 3) != 0) {
                throw new IllegalArgumentException(
                    "Compute push size must be non-negative and aligned");
            }
            if (descriptorTypes.length == 0 || shaderResources.length == 0) {
                throw new IllegalArgumentException(
                    "Shared compute program must have bindings and shaders");
            }
            long descriptorSetLayout = 0L;
            long pipelineLayout = 0L;
            long[] pipelines = new long[shaderResources.length];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(descriptorTypes.length, stack);
                for (int binding = 0; binding < descriptorTypes.length; binding++) {
                    bindings.get(binding)
                        .binding(binding)
                        .descriptorType(descriptorTypes[binding])
                        .descriptorCount(1)
                        .stageFlags(COMPUTE_STAGE);
                }
                LongBuffer pointer = stack.mallocLong(1);
                VulkanContext.check(
                    VK12.vkCreateDescriptorSetLayout(context.vkDevice(),
                        VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(
                            bindings),
                        null, pointer),
                    "create " + label + " descriptor layout");
                descriptorSetLayout = pointer.get(0);

                VkPipelineLayoutCreateInfo pipelineLayoutInfo =
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(
                        stack.longs(descriptorSetLayout));
                if (pushSize > 0) {
                    pipelineLayoutInfo.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                            .stageFlags(COMPUTE_STAGE)
                            .offset(0)
                            .size(pushSize));
                }
                pointer.clear();
                VulkanContext.check(VK12.vkCreatePipelineLayout(
                                        context.vkDevice(), pipelineLayoutInfo, null, pointer),
                    "create " + label + " pipeline layout");
                pipelineLayout = pointer.get(0);

                long finalPipelineLayout = pipelineLayout;
                ParallelPipelineCreation.run(label, pipelines.length,
                    index
                    -> pipelines[index] = createPipeline(
                           context, finalPipelineLayout, shaderResources[index], label));
                return new SharedComputeProgram(
                    context, descriptorSetLayout, pipelineLayout, pipelines);
            } catch (RuntimeException exception) {
                for (int index = pipelines.length - 1; index >= 0; index--) {
                    if (pipelines[index] != 0L) {
                        VK12.vkDestroyPipeline(context.vkDevice(), pipelines[index], null);
                    }
                }
                if (pipelineLayout != 0L) {
                    VK12.vkDestroyPipelineLayout(context.vkDevice(), pipelineLayout, null);
                }
                if (descriptorSetLayout != 0L) {
                    VK12.vkDestroyDescriptorSetLayout(
                        context.vkDevice(), descriptorSetLayout, null);
                }
                throw exception;
            }
        }

        SharedComputeProgram retain() {
            if (this.destroyed) {
                throw new IllegalStateException("Cannot retain a destroyed compute program");
            }
            this.references++;
            return this;
        }

        void release() {
            if (this.references <= 0) {
                throw new IllegalStateException("Compute program reference underflow");
            }
            this.references--;
            if (this.references == 0) {
                destroy();
            }
        }

        long descriptorSetLayout() {
            return this.descriptorSetLayout;
        }

        long pipelineLayout() {
            return this.pipelineLayout;
        }

        long pipeline(int index) {
            return this.pipelines[index];
        }

        @Override
        public void destroy() {
            if (this.destroyed) {
                return;
            }
            if (this.references != 0) {
                throw new IllegalStateException("Cannot destroy a referenced compute program");
            }
            for (int index = this.pipelines.length - 1; index >= 0; index--) {
                VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipelines[index], null);
            }
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(
                this.context.vkDevice(), this.descriptorSetLayout, null);
            this.destroyed = true;
        }

        private static long createPipeline(
            VulkanContext context, long pipelineLayout, String shaderResource, String label) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long shader = VulkanShaderModules.create(context, stack, shaderResource);
                try {
                    VkPipelineShaderStageCreateInfo stage =
                        VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType$Default()
                            .stage(COMPUTE_STAGE)
                            .module(shader)
                            .pName(stack.UTF8("main"));
                    VkComputePipelineCreateInfo.Buffer createInfo =
                        VkComputePipelineCreateInfo.calloc(1, stack);
                    createInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                    LongBuffer pointer = stack.mallocLong(1);
                    context.createComputePipeline(
                        createInfo, pointer, label + " " + shaderResource);
                    return pointer.get(0);
                } finally {
                    VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
                }
            }
        }
    }
}
