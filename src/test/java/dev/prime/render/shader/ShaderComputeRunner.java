// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Minimal headless Vulkan dispatch harness for compute shader tests.
 *
 * <p>The harness deliberately does not reuse Minecraft's renderer. {@link VulkanTestDevice} owns
 * the instance, device, queue and command pool; dispatches own their pipeline, descriptors and
 * mapped buffers. Optional immutable sampled resources remain owned by this harness for its
 * complete lifetime. This keeps Shader behavior tests independent of client initialization and
 * render state.
 */
final class ShaderComputeRunner implements AutoCloseable {
    private static final int LOCAL_SIZE = 64;
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;

    private final VulkanTestDevice testDevice;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue queue;
    private final long commandPool;
    private final List<ImageBinding> images = new ArrayList<>();
    private final List<BufferBinding> buffers = new ArrayList<>();
    private int inputBinding;
    private int outputBinding = 1;
    private boolean closed;

    private ShaderComputeRunner(VulkanTestDevice testDevice) {
        this.testDevice = testDevice;
        this.physicalDevice = testDevice.physicalDevice();
        this.device = testDevice.device();
        this.queue = testDevice.queue();
        this.commandPool = testDevice.commandPool();
    }

    static ShaderComputeRunner open() throws UnavailableException {
        return new ShaderComputeRunner(VulkanTestDevice.open());
    }

    static ShaderComputeRunner openAddressed() throws UnavailableException {
        return new ShaderComputeRunner(VulkanTestDevice.openAddressed());
    }

    /** Fixtures importing full production modules reserve I/O outside the scene binding range. */
    void useIoBindings(int inputBinding, int outputBinding) {
        requireOpen();
        if (inputBinding < 0 || outputBinding < 0 || inputBinding == outputBinding
                || !this.images.isEmpty() || !this.buffers.isEmpty()) {
            throw new IllegalArgumentException("Set distinct I/O bindings before fixture resources");
        }
        this.inputBinding = inputBinding;
        this.outputBinding = outputBinding;
    }

    void bindStorageBuffer(int binding, ByteBuffer data) {
        requireOpen();
        if (binding < 0 || binding == this.inputBinding || binding == this.outputBinding
                || this.buffers.stream().anyMatch(value -> value.binding() == binding)) {
            throw new IllegalArgumentException("Duplicate or reserved test buffer binding");
        }
        MappedBuffer buffer = createMappedBuffer(data.remaining());
        buffer.bytes().put(data.duplicate());
        this.buffers.add(new BufferBinding(binding, buffer));
    }

    void repeatImageDescriptor(int binding, int count) {
        for (int i = 0; i < this.images.size(); i++) {
            ImageBinding image = this.images.get(i);
            if (image.binding() == binding) {
                if (count < 1) throw new IllegalArgumentException("Empty descriptor array");
                this.images.set(i, new ImageBinding(binding, image.resource(), count));
                return;
            }
        }
        throw new IllegalArgumentException("No test image at binding " + binding);
    }

    /** Runs the production compiler's two-address/count push ABI with real sampled resources. */
    ByteBuffer dispatchShadowCompiler(Path shader, ByteBuffer requests, int outputBytes, int count)
            throws IOException {
        int usage = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        try (MappedBuffer source = createMappedBuffer(requests.remaining(), usage);
                MappedBuffer output = createMappedBuffer(outputBytes, usage);
                MemoryStack stack = MemoryStack.stackPush()) {
            source.bytes().put(requests.duplicate());
            zero(output.bytes());
            var info = org.lwjgl.vulkan.VkBufferDeviceAddressInfo.calloc(stack).sType$Default();
            long sourceAddress = VK12.vkGetBufferDeviceAddress(this.device, info.buffer(source.buffer()));
            long outputAddress = VK12.vkGetBufferDeviceAddress(this.device, info.buffer(output.buffer()));
            ByteBuffer push = stack.calloc(24).putLong(0, sourceAddress).putLong(8, outputAddress).putInt(16, count);
            dispatch(shader, source, output, new Workgroups((count + 63) / 64, 1, 1), push);
            ByteBuffer result = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.LITTLE_ENDIAN);
            result.put(output.bytes().duplicate().clear()).flip();
            return result;
        }
    }

    /** Relocates fixture pointers inside one owned allocation; push constants contain its base. */
    ByteBuffer dispatchAddressed(
            String artifact, ByteBuffer input, int[] pointerOffsets,
            int outputBytes, int invocationCount) throws IOException {
        requireOpen();
        try (MappedBuffer source = createMappedBuffer(input.remaining(),
                     VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
                MappedBuffer output = createMappedBuffer(outputBytes);
                MemoryStack stack = MemoryStack.stackPush()) {
            long address = VK12.vkGetBufferDeviceAddress(this.device,
                    org.lwjgl.vulkan.VkBufferDeviceAddressInfo.calloc(stack)
                            .sType$Default().buffer(source.buffer()));
            source.bytes().put(input.duplicate());
            for (int offset : pointerOffsets) {
                source.bytes().putLong(offset, Math.addExact(address, source.bytes().getLong(offset)));
            }
            zero(output.bytes());
            ByteBuffer push = stack.malloc(8).putLong(address).flip();
            dispatch(shader(artifact), source, output,
                    new Workgroups((invocationCount + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1), push);
            ByteBuffer result = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.LITTLE_ENDIAN);
            result.put(output.bytes().duplicate().clear()).flip();
            return result;
        }
    }

    private Path shader(String artifact) {
        String directory = System.getProperty("prime.test.slangShaderDirectory");
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("prime.test.slangShaderDirectory is not configured");
        }
        return Path.of(directory, artifact);
    }

    ByteBuffer dispatch(
            String shaderArtifact, ByteBuffer input, int outputBytes, int invocationCount)
            throws IOException {
        return dispatch(shaderArtifact, input, outputBytes, invocationCount, null);
    }

    ByteBuffer dispatch(
            String shaderArtifact, ByteBuffer input, int outputBytes, int invocationCount,
            ByteBuffer pushConstants)
            throws IOException {
        return dispatch(
                shaderArtifact,
                input,
                outputBytes,
                new Workgroups(Math.max(1, (invocationCount + LOCAL_SIZE - 1) / LOCAL_SIZE), 1, 1),
                pushConstants);
    }

    ByteBuffer dispatch(
            String shaderArtifact,
            ByteBuffer input,
            int outputBytes,
            Workgroups workgroups,
            ByteBuffer pushConstants)
            throws IOException {
        requireOpen();
        if (outputBytes <= 0) {
            throw new IllegalArgumentException("Shader output size must be positive");
        }
        ByteBuffer inputData = input.duplicate();
        if (!inputData.hasRemaining()) {
            throw new IllegalArgumentException("Shader input must not be empty");
        }
        ByteBuffer pushData = pushConstants == null ? null : pushConstants.duplicate();
        if (pushData != null
                && (!pushData.hasRemaining() || (pushData.remaining() & 3) != 0)) {
            throw new IllegalArgumentException(
                    "Push constants must be non-empty and word-aligned");
        }

        try (MappedBuffer inputBuffer = createMappedBuffer(inputData.remaining());
                MappedBuffer outputBuffer = createMappedBuffer(outputBytes)) {
            inputBuffer.bytes().put(inputData).flip();
            zero(outputBuffer.bytes());
            dispatch(shader(shaderArtifact), inputBuffer, outputBuffer, workgroups, pushData);

            ByteBuffer result = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer mappedOutput = outputBuffer.bytes().duplicate();
            mappedOutput.clear().limit(outputBytes);
            result.put(mappedOutput).flip();
            return result;
        }
    }

    void loadTransmissionGgxEnergy(
            ByteBuffer pixels, int width, int height, int depth) {
        bindSampledImage(
                ShaderAbi.DESCRIPTOR_TRANSMISSION_GGX_ENERGY,
                ImageDimension.THREE_D,
                ImageFormat.R16G16B16A16_SFLOAT,
                pixels,
                width,
                height,
                depth);
    }

    void bindSampledImage(
            int binding,
            ImageDimension dimension,
            ImageFormat format,
            ByteBuffer pixels,
            int width,
            int height,
            int depth) {
        bindMipmappedImage(binding, dimension, format, pixels, width, height, depth, 1, false);
    }

    void bindMipmappedImage(int binding, ImageDimension dimension, ImageFormat format,
            ByteBuffer pixels, int width, int height, int depth, int mipLevels, boolean repeatU) {
        requireOpen();
        validateImageBinding(binding, dimension, width, height, depth);
        if (mipLevels < 1 || mipLevels > 32 - Integer.numberOfLeadingZeros(Math.max(width, Math.max(height, depth)))) {
            throw new IllegalArgumentException("Invalid shader-test mip count");
        }
        int byteSize = 0;
        for (int level = 0; level < mipLevels; level++) {
            byteSize = Math.addExact(byteSize, format.byteSize(Math.max(1, width >> level),
                    Math.max(1, height >> level), Math.max(1, depth >> level)));
        }
        ByteBuffer source = pixels.duplicate();
        if (source.remaining() != byteSize) {
            throw new IllegalArgumentException(
                    "Shader-test image has "
                            + source.remaining()
                            + " bytes, expected "
                            + byteSize);
        }
        ImageResource image = createImage(
                dimension,
                format,
                width,
                height,
                depth,
                source, mipLevels, repeatU);
        this.images.add(new ImageBinding(binding, image, 1));
    }

    private ImageResource createImage(
            ImageDimension dimension,
            ImageFormat format,
            int width,
            int height,
            int depth,
            ByteBuffer pixels, int mipLevels, boolean repeatU) {
        long image = 0L;
        long memory = 0L;
        long view = 0L;
        long sampler = 0L;
        MappedBuffer upload = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            upload = createMappedBuffer(
                    pixels.remaining(), VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            upload.bytes().put(pixels).flip();
            LongBuffer handle = stack.mallocLong(1);
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(dimension.imageType())
                    .format(format.vkFormat())
                    .mipLevels(mipLevels)
                    .arrayLayers(1)
                    .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT
                            | VK12.VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, depth);
            check(
                    VK12.vkCreateImage(this.device, imageInfo, null, handle),
                    "create shader-test image");
            image = handle.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            VK12.vkGetImageMemoryRequirements(this.device, image, requirements);
            int memoryType = findMemoryType(
                    requirements.memoryTypeBits(),
                    VK12.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    stack);
            handle.clear();
            check(
                    VK12.vkAllocateMemory(
                            this.device,
                            VkMemoryAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .allocationSize(requirements.size())
                                    .memoryTypeIndex(memoryType),
                            null,
                            handle),
                    "allocate shader-test image memory");
            memory = handle.get(0);
            check(
                    VK12.vkBindImageMemory(this.device, image, memory, 0L),
                    "bind shader-test image memory");

            handle.clear();
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(image)
                    .viewType(dimension.viewType())
                    .format(format.vkFormat());
            viewInfo.subresourceRange()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(mipLevels)
                    .baseArrayLayer(0)
                    .layerCount(1);
            check(
                    VK12.vkCreateImageView(this.device, viewInfo, null, handle),
                    "create shader-test image view");
            view = handle.get(0);

            handle.clear();
            check(
                    VK12.vkCreateSampler(
                            this.device,
                            VkSamplerCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .magFilter(VK12.VK_FILTER_LINEAR)
                                    .minFilter(VK12.VK_FILTER_LINEAR)
                                    .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                                    .addressModeU(repeatU ? VK12.VK_SAMPLER_ADDRESS_MODE_REPEAT : VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                                    .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                                    .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                                    .minLod(0.0F)
                                    .maxLod(mipLevels - 1.0F)
                                    .maxAnisotropy(1.0F),
                            null,
                            handle),
                    "create shader-test sampler");
            sampler = handle.get(0);

            prepareImage(upload, image, width, height, depth, format, mipLevels);
            ImageResource result =
                    new ImageResource(this.device, image, memory, view, sampler);
            image = 0L;
            memory = 0L;
            view = 0L;
            sampler = 0L;
            return result;
        } finally {
            if (upload != null) upload.close();
            if (sampler != 0L) {
                VK12.vkDestroySampler(this.device, sampler, null);
            }
            if (view != 0L) {
                VK12.vkDestroyImageView(this.device, view, null);
            }
            if (image != 0L) {
                VK12.vkDestroyImage(this.device, image, null);
            }
            if (memory != 0L) {
                VK12.vkFreeMemory(this.device, memory, null);
            }
        }
    }

    private void validateImageBinding(
            int binding,
            ImageDimension dimension,
            int width,
            int height,
            int depth) {
        if (binding < 0 || binding == this.inputBinding || binding == this.outputBinding
                || this.images.stream().anyMatch(value -> value.binding() == binding)) {
            throw new IllegalArgumentException(
                    "Shader-test image binding must be unique and not reserved for I/O: " + binding);
        }
        if (width <= 0 || height <= 0 || depth <= 0
                || (dimension == ImageDimension.TWO_D && depth != 1)) {
            throw new IllegalArgumentException("Invalid shader-test image dimensions");
        }
    }

    private void dispatch(
            Path shaderPath,
            MappedBuffer inputBuffer,
            MappedBuffer outputBuffer,
            Workgroups workgroups,
            ByteBuffer pushConstants)
            throws IOException {
        long setLayout = 0L;
        long pipelineLayout = 0L;
        long shaderModule = 0L;
        long pipeline = 0L;
        long descriptorPool = 0L;
        VkCommandBuffer commandBuffer = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(2 + this.images.size() + this.buffers.size(), stack);
            bindings.get(0)
                    .binding(this.inputBinding)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(COMPUTE_STAGE);
            bindings.get(1)
                    .binding(this.outputBinding)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(COMPUTE_STAGE);
            for (int index = 0; index < this.images.size(); index++) {
                ImageBinding image = this.images.get(index);
                bindings.get(index + 2)
                        .binding(image.binding())
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(image.count())
                        .stageFlags(COMPUTE_STAGE);
            }
            for (int i = 0; i < this.buffers.size(); i++) {
                bindings.get(2 + this.images.size() + i).binding(this.buffers.get(i).binding())
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1).stageFlags(COMPUTE_STAGE);
            }
            check(
                    VK12.vkCreateDescriptorSetLayout(
                            this.device,
                            VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pBindings(bindings),
                            null,
                            handle),
                    "create shader-test descriptor layout");
            setLayout = handle.get(0);

            handle.clear();
            VkPipelineLayoutCreateInfo pipelineLayoutInfo =
                    VkPipelineLayoutCreateInfo.calloc(stack)
                            .sType$Default()
                            .pSetLayouts(stack.longs(setLayout));
            if (pushConstants != null) {
                VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
                range.get(0)
                        .stageFlags(COMPUTE_STAGE)
                        .offset(0)
                        .size(pushConstants.remaining());
                pipelineLayoutInfo.pPushConstantRanges(range);
            }
            check(
                    VK12.vkCreatePipelineLayout(
                            this.device,
                            pipelineLayoutInfo,
                            null,
                            handle),
                    "create shader-test pipeline layout");
            pipelineLayout = handle.get(0);

            shaderModule = createShaderModule(shaderPath, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(COMPUTE_STAGE)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo =
                    VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            handle.clear();
            check(
                    VK12.vkCreateComputePipelines(
                            this.device, 0L, pipelineInfo, null, handle),
                    "create shader-test compute pipeline");
            pipeline = handle.get(0);
            VK12.vkDestroyShaderModule(this.device, shaderModule, null);
            shaderModule = 0L;

            int poolTypeCount = this.images.isEmpty() ? 1 : 2;
            VkDescriptorPoolSize.Buffer poolSizes =
                    VkDescriptorPoolSize.calloc(poolTypeCount, stack);
            poolSizes.get(0)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(2 + this.buffers.size());
            if (!this.images.isEmpty()) {
                poolSizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(this.images.stream().mapToInt(ImageBinding::count).sum());
            }
            handle.clear();
            check(
                    VK12.vkCreateDescriptorPool(
                            this.device,
                            VkDescriptorPoolCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .maxSets(1)
                                    .pPoolSizes(poolSizes),
                            null,
                            handle),
                    "create shader-test descriptor pool");
            descriptorPool = handle.get(0);

            handle.clear();
            check(
                    VK12.vkAllocateDescriptorSets(
                            this.device,
                            VkDescriptorSetAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .descriptorPool(descriptorPool)
                                    .pSetLayouts(stack.longs(setLayout)),
                            handle),
                    "allocate shader-test descriptor set");
            long descriptorSet = handle.get(0);
            VkDescriptorBufferInfo.Buffer bufferInfos =
                    VkDescriptorBufferInfo.calloc(2 + this.buffers.size(), stack);
            bufferInfos.get(0)
                    .buffer(inputBuffer.buffer())
                    .offset(0L)
                    .range(inputBuffer.size());
            bufferInfos.get(1)
                    .buffer(outputBuffer.buffer())
                    .offset(0L)
                    .range(outputBuffer.size());
            int writeCount = 2 + this.images.size() + this.buffers.size();
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(writeCount, stack);
            writes.get(0)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(this.inputBinding)
                    .descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.create(
                            bufferInfos.get(0).address(), 1));
            writes.get(1)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(this.outputBinding)
                    .descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.create(
                            bufferInfos.get(1).address(), 1));
            VkDescriptorImageInfo.Buffer imageInfos =
                    VkDescriptorImageInfo.calloc(this.images.stream().mapToInt(ImageBinding::count).sum(), stack);
            int imageCursor = 0;
            for (int index = 0; index < this.images.size(); index++) {
                ImageBinding image = this.images.get(index);
                int first = imageCursor;
                for (int i = 0; i < image.count(); i++) {
                    imageInfos.get(imageCursor++).sampler(image.resource().sampler())
                            .imageView(image.resource().view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
                writes.get(index + 2)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(image.binding())
                        .descriptorCount(image.count())
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(VkDescriptorImageInfo.create(
                                imageInfos.get(first).address(), image.count()));
            }
            for (int i = 0; i < this.buffers.size(); i++) {
                BufferBinding buffer = this.buffers.get(i);
                bufferInfos.get(2 + i).buffer(buffer.resource().buffer()).offset(0).range(buffer.resource().size());
                writes.get(2 + this.images.size() + i).sType$Default().dstSet(descriptorSet)
                        .dstBinding(buffer.binding()).descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.create(bufferInfos.get(2 + i).address(), 1));
            }
            VK12.vkUpdateDescriptorSets(this.device, writes, null);

            PointerBuffer commandPointer = stack.mallocPointer(1);
            check(
                    VK12.vkAllocateCommandBuffers(
                            this.device,
                            VkCommandBufferAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .commandPool(this.commandPool)
                                    .level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                                    .commandBufferCount(1),
                            commandPointer),
                    "allocate shader-test command buffer");
            commandBuffer = new VkCommandBuffer(commandPointer.get(0), this.device);
            check(
                    VK12.vkBeginCommandBuffer(
                            commandBuffer,
                            VkCommandBufferBeginInfo.calloc(stack)
                                    .sType$Default()
                                    .flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),
                    "begin shader-test command buffer");
            VK12.vkCmdBindPipeline(
                    commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout,
                    0,
                    stack.longs(descriptorSet),
                    null);
            if (pushConstants != null) {
                VK12.vkCmdPushConstants(
                        commandBuffer,
                        pipelineLayout,
                        COMPUTE_STAGE,
                        0,
                        pushConstants);
            }
            VK12.vkCmdDispatch(
                    commandBuffer,
                    workgroups.x(),
                    workgroups.y(),
                    workgroups.z());
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_HOST_READ_BIT);
            VK12.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_HOST_BIT,
                    0,
                    barrier,
                    null,
                    null);
            check(VK12.vkEndCommandBuffer(commandBuffer), "end shader-test command buffer");

            VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
            submit.get(0)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(commandBuffer.address()));
            check(VK12.vkQueueSubmit(this.queue, submit, 0L), "submit shader-test dispatch");
            check(VK12.vkQueueWaitIdle(this.queue), "wait for shader-test dispatch");
        } finally {
            if (commandBuffer != null) {
                VK12.vkFreeCommandBuffers(this.device, this.commandPool, commandBuffer);
            }
            if (descriptorPool != 0L) {
                VK12.vkDestroyDescriptorPool(this.device, descriptorPool, null);
            }
            if (pipeline != 0L) {
                VK12.vkDestroyPipeline(this.device, pipeline, null);
            }
            if (shaderModule != 0L) {
                VK12.vkDestroyShaderModule(this.device, shaderModule, null);
            }
            if (pipelineLayout != 0L) {
                VK12.vkDestroyPipelineLayout(this.device, pipelineLayout, null);
            }
            if (setLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(this.device, setLayout, null);
            }
        }
    }

    private void prepareImage(
            MappedBuffer upload,
            long image,
            int width,
            int height,
            int depth, ImageFormat format, int mipLevels) {
        VkCommandBuffer commandBuffer = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer commandPointer = stack.mallocPointer(1);
            check(
                    VK12.vkAllocateCommandBuffers(
                            this.device,
                            VkCommandBufferAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .commandPool(this.commandPool)
                                    .level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                                    .commandBufferCount(1),
                            commandPointer),
                    "allocate shader-test image command buffer");
            commandBuffer = new VkCommandBuffer(commandPointer.get(0), this.device);
            check(
                    VK12.vkBeginCommandBuffer(
                            commandBuffer,
                            VkCommandBufferBeginInfo.calloc(stack)
                                    .sType$Default()
                                    .flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),
                    "begin shader-test image preparation");

            VkImageMemoryBarrier.Buffer toTransfer =
                    VkImageMemoryBarrier.calloc(1, stack);
            fillImageBarrier(
                    toTransfer.get(0),
                    image,
                    0,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK12.VK_IMAGE_LAYOUT_UNDEFINED,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            toTransfer.get(0).subresourceRange().levelCount(mipLevels);
            VK12.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    null,
                    toTransfer);

            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(mipLevels, stack);
            long offset = 0;
            for (int level = 0; level < mipLevels; level++) {
                int w = Math.max(1, width >> level), h = Math.max(1, height >> level), d = Math.max(1, depth >> level);
                copy.get(level)
                    .bufferOffset(offset)
                    .bufferRowLength(0)
                    .bufferImageHeight(0);
                copy.get(level).imageSubresource()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level)
                    .baseArrayLayer(0)
                    .layerCount(1);
                copy.get(level).imageOffset().set(0, 0, 0);
                copy.get(level).imageExtent().set(w, h, d);
                offset += format.byteSize(w, h, d);
            }
            VK12.vkCmdCopyBufferToImage(
                    commandBuffer,
                    upload.buffer(),
                    image,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    copy);

            VkImageMemoryBarrier.Buffer toShader = VkImageMemoryBarrier.calloc(1, stack);
            fillImageBarrier(
                    toShader.get(0),
                    image,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            toShader.get(0).subresourceRange().levelCount(mipLevels);
            VK12.vkCmdPipelineBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    null,
                    null,
                    toShader);
            check(VK12.vkEndCommandBuffer(commandBuffer), "end shader-test image preparation");

            VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
            submit.get(0)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(commandBuffer.address()));
            check(
                    VK12.vkQueueSubmit(this.queue, submit, 0L),
                    "submit shader-test image preparation");
            check(
                    VK12.vkQueueWaitIdle(this.queue),
                    "wait for shader-test image preparation");
        } finally {
            if (commandBuffer != null) {
                VK12.vkFreeCommandBuffers(
                        this.device, this.commandPool, commandBuffer);
            }
        }
    }

    private static void fillImageBarrier(
            VkImageMemoryBarrier barrier,
            long image,
            int sourceAccess,
            int destinationAccess,
            int oldLayout,
            int newLayout) {
        barrier.sType$Default()
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    private MappedBuffer createMappedBuffer(int size) {
        return createMappedBuffer(size, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    }

    private MappedBuffer createMappedBuffer(int size, int usage) {
        long buffer = 0L;
        long memory = 0L;
        boolean mappedMemory = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            check(
                    VK12.vkCreateBuffer(
                            this.device,
                            VkBufferCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .size(size)
                                    .usage(usage)
                                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE),
                            null,
                            handle),
                    "create shader-test buffer");
            buffer = handle.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            VK12.vkGetBufferMemoryRequirements(this.device, buffer, requirements);
            int memoryType = findMemoryType(
                    requirements.memoryTypeBits(),
                    VK12.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                            | VK12.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    stack);
            handle.clear();
            var allocation = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default().allocationSize(requirements.size()).memoryTypeIndex(memoryType);
            if ((usage & VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
                allocation.pNext(org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType$Default().flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT).address());
            }
            check(
                    VK12.vkAllocateMemory(
                            this.device,
                            allocation,
                            null,
                            handle),
                    "allocate shader-test buffer memory");
            memory = handle.get(0);
            check(VK12.vkBindBufferMemory(this.device, buffer, memory, 0L),
                    "bind shader-test buffer memory");

            PointerBuffer mapped = stack.mallocPointer(1);
            check(
                    VK12.vkMapMemory(this.device, memory, 0L, size, 0, mapped),
                    "map shader-test buffer memory");
            mappedMemory = true;
            ByteBuffer bytes = MemoryUtil.memByteBuffer(mapped.get(0), size)
                    .order(ByteOrder.LITTLE_ENDIAN);
            return new MappedBuffer(this.device, buffer, memory, bytes, size);
        } catch (RuntimeException exception) {
            if (mappedMemory) {
                VK12.vkUnmapMemory(this.device, memory);
            }
            if (memory != 0L) {
                VK12.vkFreeMemory(this.device, memory, null);
            }
            if (buffer != 0L) {
                VK12.vkDestroyBuffer(this.device, buffer, null);
            }
            throw exception;
        }
    }

    private int findMemoryType(
            int memoryTypeBits, int required, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties properties =
                VkPhysicalDeviceMemoryProperties.calloc(stack);
        VK12.vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, properties);
        for (int index = 0; index < properties.memoryTypeCount(); index++) {
            if ((memoryTypeBits & (1 << index)) != 0
                    && (properties.memoryTypes(index).propertyFlags() & required) == required) {
                return index;
            }
        }
        throw new IllegalStateException(
                "Vulkan compute device has no memory type with flags 0x"
                        + Integer.toHexString(required));
    }

    private long createShaderModule(Path shaderPath, MemoryStack stack) throws IOException {
        byte[] bytes = Files.readAllBytes(shaderPath);
        if (bytes.length == 0 || (bytes.length & 3) != 0) {
            throw new IllegalArgumentException("Shader module is empty or not word-aligned: " + shaderPath);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            LongBuffer handle = stack.mallocLong(1);
            check(
                    VK12.vkCreateShaderModule(
                            this.device,
                            VkShaderModuleCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pCode(code),
                            null,
                            handle),
                    "create shader-test module " + shaderPath.getFileName());
            return handle.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static void zero(ByteBuffer buffer) {
        ByteBuffer target = buffer.duplicate();
        target.clear();
        while (target.remaining() >= Long.BYTES) {
            target.putLong(0L);
        }
        while (target.hasRemaining()) {
            target.put((byte) 0);
        }
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with Vulkan result " + result);
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Shader compute runner is closed");
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        Throwable failure = null;
        try {
            this.testDevice.waitIdle();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        for (ImageBinding image : this.images) {
            try {
                image.resource().close();
            } catch (RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        this.images.clear();
        for (BufferBinding buffer : this.buffers) buffer.resource().close();
        this.buffers.clear();
        try {
            this.testDevice.close();
        } catch (RuntimeException | Error exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    static final class UnavailableException extends Exception {
        private static final long serialVersionUID = 1L;

        UnavailableException(String message) {
            super(message);
        }
    }

    record Workgroups(int x, int y, int z) {
        Workgroups {
            if (x <= 0 || y <= 0 || z <= 0) {
                throw new IllegalArgumentException("Workgroup counts must be positive");
            }
        }
    }

    enum ImageDimension {
        TWO_D(VK12.VK_IMAGE_TYPE_2D, VK12.VK_IMAGE_VIEW_TYPE_2D),
        THREE_D(VK12.VK_IMAGE_TYPE_3D, VK12.VK_IMAGE_VIEW_TYPE_3D);

        private final int imageType;
        private final int viewType;

        ImageDimension(int imageType, int viewType) {
            this.imageType = imageType;
            this.viewType = viewType;
        }

        int imageType() {
            return this.imageType;
        }

        int viewType() {
            return this.viewType;
        }
    }

    enum ImageFormat {
        R8G8B8A8_UNORM(VK12.VK_FORMAT_R8G8B8A8_UNORM, 4),
        R8G8B8A8_SRGB(VK12.VK_FORMAT_R8G8B8A8_SRGB, 4),
        R16G16B16A16_SFLOAT(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, 4 * Short.BYTES),
        BC6H_UFLOAT_BLOCK(VK12.VK_FORMAT_BC6H_UFLOAT_BLOCK, 16, 4);

        private final int vkFormat;
        private final int blockBytes;
        private final int blockExtent;

        ImageFormat(int vkFormat, int bytesPerPixel) {
            this(vkFormat, bytesPerPixel, 1);
        }

        ImageFormat(int vkFormat, int blockBytes, int blockExtent) {
            this.vkFormat = vkFormat;
            this.blockBytes = blockBytes;
            this.blockExtent = blockExtent;
        }

        int vkFormat() {
            return this.vkFormat;
        }

        int byteSize(int width, int height, int depth) {
            int blocks = Math.multiplyExact(
                    (width + this.blockExtent - 1) / this.blockExtent,
                    (height + this.blockExtent - 1) / this.blockExtent);
            return Math.multiplyExact(Math.multiplyExact(blocks, depth), this.blockBytes);
        }
    }

    private record MappedBuffer(
            VkDevice device,
            long buffer,
            long memory,
            ByteBuffer bytes,
            int size)
            implements AutoCloseable {
        @Override
        public void close() {
            VK12.vkUnmapMemory(this.device, this.memory);
            VK12.vkDestroyBuffer(this.device, this.buffer, null);
            VK12.vkFreeMemory(this.device, this.memory, null);
        }
    }

    private record BufferBinding(int binding, MappedBuffer resource) {}

    private record ImageBinding(int binding, ImageResource resource, int count) {
    }

    private record ImageResource(
            VkDevice device,
            long image,
            long memory,
            long view,
            long sampler)
            implements AutoCloseable {
        @Override
        public void close() {
            if (this.sampler != 0L) {
                VK12.vkDestroySampler(this.device, this.sampler, null);
            }
            VK12.vkDestroyImageView(this.device, this.view, null);
            VK12.vkDestroyImage(this.device, this.image, null);
            VK12.vkFreeMemory(this.device, this.memory, null);
        }
    }
}
