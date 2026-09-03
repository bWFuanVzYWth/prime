package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.shader.ShaderAbi;
import java.nio.LongBuffer;
import java.util.Arrays;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

/** Size-independent programs shared by extent-scoped descriptor and image resources. */
public final class VulkanSharedPrograms implements AutoCloseable {
    private static final int SAMPLED_IMAGE = VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
    private static final int STORAGE_IMAGE = VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    private static final int STORAGE_BUFFER = VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    private enum Program {
        DISPLAY_TRANSFORM("common display-transform", 28, true,
                new int[] {SAMPLED_IMAGE, STORAGE_IMAGE, STORAGE_BUFFER, STORAGE_IMAGE},
                "fsr_display"),
        AUTO_EXPOSURE("auto-exposure", 16, true,
                new int[] {
                    SAMPLED_IMAGE, STORAGE_IMAGE, STORAGE_IMAGE, STORAGE_BUFFER, STORAGE_BUFFER
                },
                "auto_exposure_histogram", "auto_exposure_update"),
        HDR_PRESENT("HDR presentation", 16, true,
                new int[] {SAMPLED_IMAGE, SAMPLED_IMAGE, SAMPLED_IMAGE, STORAGE_IMAGE},
                "hdr_present"),
        UI_ALPHA_CLEAR("UI alpha clear", 8, true,
                new int[] {STORAGE_IMAGE}, "ui_alpha_clear"),
        UI_ALPHA_EXTRACT("UI alpha extraction", 8, true,
                new int[] {SAMPLED_IMAGE, STORAGE_IMAGE}, "ui_alpha_extract"),
        STREAMLINE_INPUT("Streamline input preparation", ShaderAbi.NRD_MOTION_PUSH_CONSTANT_SIZE,
                false,
                new int[] {
                    SAMPLED_IMAGE, SAMPLED_IMAGE, STORAGE_IMAGE, STORAGE_IMAGE, STORAGE_IMAGE
                },
                "streamline_input");

        final String label;
        final int pushSize;
        final boolean prewarm;
        final int[] descriptorTypes;
        final String[] shaderResources;

        Program(String label, int pushSize, boolean prewarm, int[] descriptorTypes, String... shaders) {
            this.label = label;
            this.pushSize = pushSize;
            this.prewarm = prewarm;
            this.descriptorTypes = descriptorTypes;
            this.shaderResources = Arrays.stream(shaders)
                    .map(GeneratedShaderPrograms::resource)
                    .toArray(String[]::new);
        }
    }

    private final VulkanContext context;
    private final SharedComputeProgram[] programs = new SharedComputeProgram[Program.values().length];
    private boolean closed;

    VulkanSharedPrograms(VulkanContext context) {
        this.context = context;
    }

    void prewarm() {
        for (Program program : Program.values()) {
            if (program.prewarm) {
                acquire(program).release();
            }
        }
    }

    SharedComputeProgram acquireDisplayTransform() {
        return acquire(Program.DISPLAY_TRANSFORM);
    }

    SharedComputeProgram acquireAutoExposure() {
        return acquire(Program.AUTO_EXPOSURE);
    }

    SharedComputeProgram acquireHdrPresent() {
        return acquire(Program.HDR_PRESENT);
    }

    SharedComputeProgram acquireUiAlphaClear() {
        return acquire(Program.UI_ALPHA_CLEAR);
    }

    SharedComputeProgram acquireUiAlphaExtract() {
        return acquire(Program.UI_ALPHA_EXTRACT);
    }

    SharedComputeProgram acquireStreamlineInput() {
        return acquire(Program.STREAMLINE_INPUT);
    }

    private SharedComputeProgram acquire(Program program) {
        requireOpen();
        int index = program.ordinal();
        if (this.programs[index] == null) {
            this.programs[index] = SharedComputeProgram.create(
                    this.context,
                    program.label,
                    program.pushSize,
                    program.descriptorTypes,
                    program.shaderResources);
        }
        return this.programs[index].retain();
    }

    void invalidate() {
        requireOpen();
        for (int index = 0; index < this.programs.length; index++) {
            if (this.programs[index] != null) {
                this.programs[index].release();
                this.programs[index] = null;
            }
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

    public static final class SharedComputeProgram implements Destroyable {
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

        public static SharedComputeProgram create(VulkanContext context, String label, int pushSize,
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
                    VulkanDescriptors.layoutBinding(
                            bindings.get(binding), binding,
                            descriptorTypes[binding], 1, COMPUTE_STAGE);
                }
                descriptorSetLayout = VulkanDescriptors.createSetLayout(
                    context, stack, bindings, "create " + label + " descriptor layout");
                VkPushConstantRange.Buffer pushConstants = pushSize > 0
                    ? VkPushConstantRange.calloc(1, stack)
                            .stageFlags(COMPUTE_STAGE)
                            .offset(0)
                            .size(pushSize)
                    : null;
                pipelineLayout = VulkanDescriptors.createPipelineLayout(
                    context,
                    stack,
                    descriptorSetLayout,
                    pushConstants,
                    "create " + label + " pipeline layout");

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

        public static SharedComputeProgram createStorageImages(
                VulkanContext context,
                String label,
                int pushSize,
                int imageCount,
                String shaderResource) {
            int[] descriptorTypes = new int[imageCount];
            Arrays.fill(descriptorTypes, STORAGE_IMAGE);
            return create(
                    context,
                    label,
                    pushSize,
                    descriptorTypes,
                    new String[] {shaderResource});
        }

        SharedComputeProgram retain() {
            if (this.destroyed) {
                throw new IllegalStateException("Cannot retain a destroyed compute program");
            }
            this.references++;
            return this;
        }

        public void release() {
            if (this.references <= 0) {
                throw new IllegalStateException("Compute program reference underflow");
            }
            this.references--;
            if (this.references == 0) {
                destroy();
            }
        }

        public long descriptorSetLayout() {
            return this.descriptorSetLayout;
        }

        public long pipelineLayout() {
            return this.pipelineLayout;
        }

        public long pipeline(int index) {
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
