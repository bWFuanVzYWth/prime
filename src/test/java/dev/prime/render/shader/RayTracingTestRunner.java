package dev.prime.render.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

/** Minimal validated BLAS/TLAS, ray-pipeline, SBT and readback harness. */
final class RayTracingTestRunner implements AutoCloseable {
    static final int RAY_COUNT = 2;
    static final int WORDS_PER_RAY = 6;

    private final VulkanTestDevice testDevice;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue queue;
    private final long commandPool;
    private boolean closed;

    private RayTracingTestRunner(VulkanTestDevice testDevice) {
        this.testDevice = testDevice;
        this.physicalDevice = testDevice.physicalDevice();
        this.device = testDevice.device();
        this.queue = testDevice.queue();
        this.commandPool = testDevice.commandPool();
    }

    static RayTracingTestRunner open() throws ShaderComputeRunner.UnavailableException {
        return new RayTracingTestRunner(VulkanTestDevice.openRayTracing());
    }

    ByteBuffer trace(Path shaderDirectory) throws IOException {
        requireOpen();
        RtProperties properties = properties();
        try (MappedBuffer vertices = createMappedBuffer(
                        9L * Float.BYTES,
                        KHRAccelerationStructure
                                        .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                        true)) {
            vertices.bytes()
                    .putFloat(-1.0F).putFloat(-1.0F).putFloat(0.0F)
                    .putFloat(1.0F).putFloat(-1.0F).putFloat(0.0F)
                    .putFloat(0.0F).putFloat(1.0F).putFloat(0.0F)
                    .flip();
            try (AccelerationBuild bottom = createBottomLevel(vertices, properties);
                    MappedBuffer instances = createMappedBuffer(
                            VkAccelerationStructureInstanceKHR.SIZEOF,
                            KHRAccelerationStructure
                                            .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                                    | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                            true)) {
                writeInstance(instances, bottom.structure().deviceAddress());
                try (AccelerationBuild top = createTopLevel(instances, properties);
                        MappedBuffer output = createMappedBuffer(
                                (long) RAY_COUNT * WORDS_PER_RAY * Integer.BYTES,
                                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                false);
                        TraceResources trace = createTraceResources(
                                shaderDirectory, top.structure().handle(), output);
                        MappedBuffer sbt = createShaderBindingTable(
                                trace.pipeline(), properties)) {
                    zero(output.bytes());
                    recordAndSubmit(
                            vertices,
                            instances,
                            bottom,
                            top,
                            trace,
                            sbt,
                            output,
                            properties);
                    ByteBuffer result = ByteBuffer.allocateDirect((int) output.size())
                            .order(ByteOrder.LITTLE_ENDIAN);
                    ByteBuffer source = output.bytes().duplicate();
                    source.clear();
                    result.put(source).flip();
                    return result;
                }
            }
        }
    }

    private AccelerationBuild createBottomLevel(
            MappedBuffer vertices,
            RtProperties properties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry =
                    bottomGeometry(stack, vertices.deviceAddress());
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                            .sType$Default()
                            .type(KHRAccelerationStructure
                                    .VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                            .flags(KHRAccelerationStructure
                                    .VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                            .mode(KHRAccelerationStructure
                                    .VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                            .geometryCount(1)
                            .pGeometries(geometry);
            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                    this.device,
                    KHRAccelerationStructure
                            .VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo,
                    stack.ints(1),
                    sizes);
            TestAccelerationStructure structure = createAccelerationStructure(
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                    sizes.accelerationStructureSize());
            try {
                return new AccelerationBuild(
                        structure,
                        createScratch(sizes.buildScratchSize(), properties.scratchAlignment()));
            } catch (RuntimeException exception) {
                structure.close();
                throw exception;
            }
        }
    }

    private AccelerationBuild createTopLevel(
            MappedBuffer instances,
            RtProperties properties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry =
                    topGeometry(stack, instances.deviceAddress());
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                            .sType$Default()
                            .type(KHRAccelerationStructure
                                    .VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                            .flags(KHRAccelerationStructure
                                    .VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                            .mode(KHRAccelerationStructure
                                    .VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                            .geometryCount(1)
                            .pGeometries(geometry);
            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                    this.device,
                    KHRAccelerationStructure
                            .VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo,
                    stack.ints(1),
                    sizes);
            TestAccelerationStructure structure = createAccelerationStructure(
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
                    sizes.accelerationStructureSize());
            try {
                return new AccelerationBuild(
                        structure,
                        createScratch(sizes.buildScratchSize(), properties.scratchAlignment()));
            } catch (RuntimeException exception) {
                structure.close();
                throw exception;
            }
        }
    }

    private MappedBuffer createScratch(long required, int alignment) {
        return createMappedBuffer(
                Math.addExact(required, alignment - 1L),
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                true);
    }

    private TestAccelerationStructure createAccelerationStructure(int type, long size) {
        MappedBuffer backing = createMappedBuffer(
                size,
                KHRAccelerationStructure
                                .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                        | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                true);
        long handle = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pointer = stack.mallocLong(1);
            check(
                    KHRAccelerationStructure.vkCreateAccelerationStructureKHR(
                            this.device,
                            VkAccelerationStructureCreateInfoKHR.calloc(stack)
                                    .sType$Default()
                                    .buffer(backing.buffer())
                                    .offset(0L)
                                    .size(size)
                                    .type(type),
                            null,
                            pointer),
                    "create ray-test acceleration structure");
            handle = pointer.get(0);
            long address = KHRAccelerationStructure
                    .vkGetAccelerationStructureDeviceAddressKHR(
                            this.device,
                            VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                                    .sType$Default()
                                    .accelerationStructure(handle));
            return new TestAccelerationStructure(this.device, handle, address, backing);
        } catch (RuntimeException exception) {
            if (handle != 0L) {
                KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(
                        this.device, handle, null);
            }
            backing.close();
            throw exception;
        }
    }

    private TraceResources createTraceResources(
            Path shaderDirectory,
            long topLevel,
            MappedBuffer output) throws IOException {
        long setLayout = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        long descriptorPool = 0L;
        long[] modules = new long[4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0)
                    .binding(0)
                    .descriptorType(KHRAccelerationStructure
                            .VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            check(
                    VK12.vkCreateDescriptorSetLayout(
                            this.device,
                            VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pBindings(bindings),
                            null,
                            handle),
                    "create ray-test descriptor set layout");
            setLayout = handle.get(0);

            handle.clear();
            check(
                    VK12.vkCreatePipelineLayout(
                            this.device,
                            VkPipelineLayoutCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pSetLayouts(stack.longs(setLayout)),
                            null,
                            handle),
                    "create ray-test pipeline layout");
            pipelineLayout = handle.get(0);

            modules[0] = createShaderModule(
                    shaderDirectory.resolve("ray_tracing_execution.rgen.spv"), stack);
            modules[1] = createShaderModule(
                    shaderDirectory.resolve("ray_tracing_execution.rmiss.spv"), stack);
            modules[2] = createShaderModule(
                    shaderDirectory.resolve("ray_tracing_execution.rchit.spv"), stack);
            modules[3] = createShaderModule(
                    shaderDirectory.resolve("ray_tracing_execution.rahit.spv"), stack);
            ByteBuffer main = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages =
                    VkPipelineShaderStageCreateInfo.calloc(4, stack);
            fillStage(stages.get(0), modules[0],
                    KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR, main);
            fillStage(stages.get(1), modules[1],
                    KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR, main);
            fillStage(stages.get(2), modules[2],
                    KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, main);
            fillStage(stages.get(3), modules[3],
                    KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR, main);
            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups =
                    VkRayTracingShaderGroupCreateInfoKHR.calloc(3, stack);
            generalGroup(groups.get(0), 0);
            generalGroup(groups.get(1), 1);
            triangleGroup(groups.get(2), 2, 3);
            VkRayTracingPipelineCreateInfoKHR.Buffer pipelineInfo =
                    VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            pipelineInfo.get(0)
                    .sType$Default()
                    .pStages(stages)
                    .pGroups(groups)
                    .maxPipelineRayRecursionDepth(1)
                    .layout(pipelineLayout);
            handle.clear();
            check(
                    KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR(
                            this.device, 0L, 0L, pipelineInfo, null, handle),
                    "create ray-test pipeline");
            pipeline = handle.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0)
                    .type(KHRAccelerationStructure
                            .VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1);
            poolSizes.get(1)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1);
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
                    "create ray-test descriptor pool");
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
                    "allocate ray-test descriptor set");
            long descriptorSet = handle.get(0);
            VkWriteDescriptorSetAccelerationStructureKHR acceleration =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                            .sType$Default()
                            .pAccelerationStructures(stack.longs(topLevel));
            VkDescriptorBufferInfo.Buffer outputInfo =
                    VkDescriptorBufferInfo.calloc(1, stack);
            outputInfo.get(0)
                    .buffer(output.buffer())
                    .offset(0L)
                    .range(output.size());
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0)
                    .sType$Default()
                    .pNext(acceleration.address())
                    .dstSet(descriptorSet)
                    .dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(KHRAccelerationStructure
                            .VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            writes.get(1)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(1)
                    .descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(outputInfo);
            VK12.vkUpdateDescriptorSets(this.device, writes, null);
            return new TraceResources(
                    this.device,
                    setLayout,
                    pipelineLayout,
                    pipeline,
                    descriptorPool,
                    descriptorSet);
        } catch (IOException | RuntimeException exception) {
            closeTraceHandles(setLayout, pipelineLayout, pipeline, descriptorPool);
            throw exception;
        } finally {
            for (long module : modules) {
                if (module != 0L) {
                    VK12.vkDestroyShaderModule(this.device, module, null);
                }
            }
        }
    }

    private MappedBuffer createShaderBindingTable(
            long pipeline,
            RtProperties properties) {
        int stride = alignUp(properties.handleSize(), properties.handleAlignment());
        long capacity = Math.addExact(
                Math.multiplyExact(3L, properties.baseAlignment()),
                stride);
        MappedBuffer sbt = createMappedBuffer(
                capacity,
                KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR
                        | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                true);
        try {
            ByteBuffer handles = MemoryUtil.memAlloc(3 * properties.handleSize());
            try {
                check(
                        KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(
                                this.device, pipeline, 0, 3, handles),
                        "read ray-test shader group handles");
                long raygen = alignUp(sbt.deviceAddress(), properties.baseAlignment());
                long miss = alignUp(raygen + stride, properties.baseAlignment());
                long hit = alignUp(miss + stride, properties.baseAlignment());
                if (hit + stride > sbt.deviceAddress() + sbt.size()) {
                    throw new IllegalStateException("Ray-test SBT allocation is too small");
                }
                long source = MemoryUtil.memAddress(handles);
                long mapped = MemoryUtil.memAddress(sbt.bytes());
                long raygenMapped = mapped + raygen - sbt.deviceAddress();
                long missMapped = mapped + miss - sbt.deviceAddress();
                long hitMapped = mapped + hit - sbt.deviceAddress();
                MemoryUtil.memCopy(source, raygenMapped, properties.handleSize());
                MemoryUtil.memCopy(
                        source + properties.handleSize(),
                        missMapped,
                        properties.handleSize());
                MemoryUtil.memCopy(
                        source + 2L * properties.handleSize(),
                        hitMapped,
                        properties.handleSize());
            } finally {
                MemoryUtil.memFree(handles);
            }
            return sbt;
        } catch (RuntimeException exception) {
            sbt.close();
            throw exception;
        }
    }

    private void recordAndSubmit(
            MappedBuffer vertices,
            MappedBuffer instances,
            AccelerationBuild bottom,
            AccelerationBuild top,
            TraceResources trace,
            MappedBuffer sbt,
            MappedBuffer output,
            RtProperties properties) {
        VkCommandBuffer commandBuffer = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            check(
                    VK12.vkAllocateCommandBuffers(
                            this.device,
                            VkCommandBufferAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .commandPool(this.commandPool)
                                    .level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                                    .commandBufferCount(1),
                            pointer),
                    "allocate ray-test command buffer");
            commandBuffer = new VkCommandBuffer(pointer.get(0), this.device);
            check(
                    VK12.vkBeginCommandBuffer(
                            commandBuffer,
                            VkCommandBufferBeginInfo.calloc(stack)
                                    .sType$Default()
                                    .flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),
                    "begin ray-test command buffer");
            memoryBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_HOST_BIT,
                    KHRAccelerationStructure
                            .VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK12.VK_ACCESS_HOST_WRITE_BIT,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR,
                    stack);
            recordBottomBuild(commandBuffer, vertices, bottom, properties, stack);
            memoryBarrier(
                    commandBuffer,
                    KHRAccelerationStructure
                            .VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure
                            .VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR,
                    stack);
            recordTopBuild(commandBuffer, instances, top, properties, stack);
            memoryBarrier(
                    commandBuffer,
                    KHRAccelerationStructure
                            .VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR,
                    stack);

            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                    trace.pipeline());
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                    trace.pipelineLayout(),
                    0,
                    stack.longs(trace.descriptorSet()),
                    null);
            int stride = alignUp(properties.handleSize(), properties.handleAlignment());
            long raygenAddress = alignUp(sbt.deviceAddress(), properties.baseAlignment());
            long missAddress = alignUp(
                    raygenAddress + stride, properties.baseAlignment());
            long hitAddress = alignUp(
                    missAddress + stride, properties.baseAlignment());
            VkStridedDeviceAddressRegionKHR raygen =
                    region(stack, raygenAddress, stride);
            VkStridedDeviceAddressRegionKHR miss =
                    region(stack, missAddress, stride);
            VkStridedDeviceAddressRegionKHR hit =
                    region(stack, hitAddress, stride);
            KHRRayTracingPipeline.vkCmdTraceRaysKHR(
                    commandBuffer,
                    raygen,
                    miss,
                    hit,
                    VkStridedDeviceAddressRegionKHR.calloc(stack),
                    RAY_COUNT,
                    1,
                    1);
            memoryBarrier(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_PIPELINE_STAGE_HOST_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_ACCESS_HOST_READ_BIT,
                    stack);
            check(VK12.vkEndCommandBuffer(commandBuffer), "end ray-test command buffer");
            VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
            submit.get(0)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(commandBuffer.address()));
            check(VK12.vkQueueSubmit(this.queue, submit, 0L), "submit ray-test commands");
            check(VK12.vkQueueWaitIdle(this.queue), "wait for ray-test commands");
            output.bytes().position(0);
        } finally {
            if (commandBuffer != null) {
                VK12.vkFreeCommandBuffers(this.device, this.commandPool, commandBuffer);
            }
        }
    }

    private static void recordBottomBuild(
            VkCommandBuffer commandBuffer,
            MappedBuffer vertices,
            AccelerationBuild build,
            RtProperties properties,
            MemoryStack stack) {
        VkAccelerationStructureGeometryKHR.Buffer geometry =
                bottomGeometry(stack, vertices.deviceAddress());
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer info =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        info.get(0)
                .sType$Default()
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure
                        .VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1)
                .pGeometries(geometry)
                .dstAccelerationStructure(build.structure().handle());
        info.get(0).scratchData().deviceAddress(
                alignUp(build.scratch().deviceAddress(), properties.scratchAlignment()));
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(1).primitiveOffset(0).firstVertex(0).transformOffset(0);
        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(
                commandBuffer,
                info,
                stack.pointers(range.address()));
    }

    private static void recordTopBuild(
            VkCommandBuffer commandBuffer,
            MappedBuffer instances,
            AccelerationBuild build,
            RtProperties properties,
            MemoryStack stack) {
        VkAccelerationStructureGeometryKHR.Buffer geometry =
                topGeometry(stack, instances.deviceAddress());
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer info =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        info.get(0)
                .sType$Default()
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(KHRAccelerationStructure
                        .VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1)
                .pGeometries(geometry)
                .dstAccelerationStructure(build.structure().handle());
        info.get(0).scratchData().deviceAddress(
                alignUp(build.scratch().deviceAddress(), properties.scratchAlignment()));
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(1).primitiveOffset(0).firstVertex(0).transformOffset(0);
        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(
                commandBuffer,
                info,
                stack.pointers(range.address()));
    }

    private static VkAccelerationStructureGeometryKHR.Buffer bottomGeometry(
            MemoryStack stack,
            long vertexAddress) {
        VkAccelerationStructureGeometryKHR.Buffer geometry =
                VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0)
                .sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(0);
        geometry.get(0).geometry().triangles()
                .sType$Default()
                .vertexFormat(VK12.VK_FORMAT_R32G32B32_SFLOAT)
                .vertexStride(3L * Float.BYTES)
                .maxVertex(2)
                .indexType(KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR);
        geometry.get(0).geometry().triangles().vertexData().deviceAddress(vertexAddress);
        return geometry;
    }

    private static VkAccelerationStructureGeometryKHR.Buffer topGeometry(
            MemoryStack stack,
            long instanceAddress) {
        VkAccelerationStructureGeometryKHR.Buffer geometry =
                VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0)
                .sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR);
        geometry.get(0).geometry().instances()
                .sType$Default()
                .arrayOfPointers(false);
        geometry.get(0).geometry().instances().data().deviceAddress(instanceAddress);
        return geometry;
    }

    private static void writeInstance(MappedBuffer target, long bottomLevelAddress) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureInstanceKHR instance =
                    VkAccelerationStructureInstanceKHR.calloc(stack);
            instance.transform().matrix(stack.floats(
                    1.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 1.0F, 0.0F));
            instance.instanceCustomIndex(7)
                    .mask(0xff)
                    .instanceShaderBindingTableRecordOffset(0)
                    .flags(KHRAccelerationStructure
                            .VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                    .accelerationStructureReference(bottomLevelAddress);
            MemoryUtil.memCopy(
                    instance.address(),
                    MemoryUtil.memAddress(target.bytes()),
                    VkAccelerationStructureInstanceKHR.SIZEOF);
        }
    }

    private RtProperties properties() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties2 properties =
                    VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            VkPhysicalDeviceAccelerationStructurePropertiesKHR acceleration =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack)
                            .sType$Default();
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR rayTracing =
                    VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack)
                            .sType$Default();
            properties.pNext(acceleration.address());
            acceleration.pNext(rayTracing.address());
            VK12.vkGetPhysicalDeviceProperties2(this.physicalDevice, properties);
            return new RtProperties(
                    rayTracing.shaderGroupHandleSize(),
                    rayTracing.shaderGroupHandleAlignment(),
                    rayTracing.shaderGroupBaseAlignment(),
                    acceleration.minAccelerationStructureScratchOffsetAlignment());
        }
    }

    private MappedBuffer createMappedBuffer(long size, int usage, boolean addressable) {
        if (size <= 0L || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid ray-test buffer size " + size);
        }
        long buffer = 0L;
        long memory = 0L;
        boolean mapped = false;
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
                    "create ray-test buffer");
            buffer = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            VK12.vkGetBufferMemoryRequirements(this.device, buffer, requirements);
            int memoryType = findMemoryType(
                    requirements.memoryTypeBits(),
                    VK12.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                            | VK12.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    stack);
            VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(memoryType);
            if (addressable) {
                allocation.pNext(VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT)
                        .address());
            }
            handle.clear();
            check(
                    VK12.vkAllocateMemory(this.device, allocation, null, handle),
                    "allocate ray-test buffer memory");
            memory = handle.get(0);
            check(
                    VK12.vkBindBufferMemory(this.device, buffer, memory, 0L),
                    "bind ray-test buffer memory");
            PointerBuffer mappedPointer = stack.mallocPointer(1);
            check(
                    VK12.vkMapMemory(this.device, memory, 0L, size, 0, mappedPointer),
                    "map ray-test buffer memory");
            mapped = true;
            ByteBuffer bytes = MemoryUtil.memByteBuffer(mappedPointer.get(0), (int) size)
                    .order(ByteOrder.LITTLE_ENDIAN);
            long address = addressable
                    ? VK12.vkGetBufferDeviceAddress(
                            this.device,
                            VkBufferDeviceAddressInfo.calloc(stack)
                                    .sType$Default()
                                    .buffer(buffer))
                    : 0L;
            return new MappedBuffer(this.device, buffer, memory, bytes, size, address);
        } catch (RuntimeException exception) {
            if (mapped) {
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

    private int findMemoryType(int bits, int required, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties properties =
                VkPhysicalDeviceMemoryProperties.calloc(stack);
        VK12.vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, properties);
        for (int index = 0; index < properties.memoryTypeCount(); index++) {
            if ((bits & (1 << index)) != 0
                    && (properties.memoryTypes(index).propertyFlags() & required) == required) {
                return index;
            }
        }
        throw new IllegalStateException(
                "Ray-tracing device has no coherent host-visible buffer memory");
    }

    private long createShaderModule(Path path, MemoryStack stack) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || (bytes.length & 3) != 0) {
            throw new IllegalArgumentException("Invalid ray-test SPIR-V " + path);
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
                    "create ray-test shader module " + path.getFileName());
            return handle.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private void closeTraceHandles(
            long setLayout,
            long pipelineLayout,
            long pipeline,
            long descriptorPool) {
        if (descriptorPool != 0L) {
            VK12.vkDestroyDescriptorPool(this.device, descriptorPool, null);
        }
        if (pipeline != 0L) {
            VK12.vkDestroyPipeline(this.device, pipeline, null);
        }
        if (pipelineLayout != 0L) {
            VK12.vkDestroyPipelineLayout(this.device, pipelineLayout, null);
        }
        if (setLayout != 0L) {
            VK12.vkDestroyDescriptorSetLayout(this.device, setLayout, null);
        }
    }

    private static void fillStage(
            VkPipelineShaderStageCreateInfo stage,
            long module,
            int stageFlag,
            ByteBuffer main) {
        stage.sType$Default().stage(stageFlag).module(module).pName(main);
    }

    private static void generalGroup(
            VkRayTracingShaderGroupCreateInfoKHR group,
            int shader) {
        group.sType$Default()
                .type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                .generalShader(shader)
                .closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
    }

    private static void triangleGroup(
            VkRayTracingShaderGroupCreateInfoKHR group,
            int closestHit,
            int anyHit) {
        group.sType$Default()
                .type(KHRRayTracingPipeline
                        .VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                .generalShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .closestHitShader(closestHit)
                .anyHitShader(anyHit)
                .intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
    }

    private static void memoryBarrier(
            VkCommandBuffer commandBuffer,
            int sourceStage,
            int destinationStage,
            int sourceAccess,
            int destinationAccess,
            MemoryStack stack) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess);
        VK12.vkCmdPipelineBarrier(
                commandBuffer,
                sourceStage,
                destinationStage,
                0,
                barrier,
                null,
                null);
    }

    private static VkStridedDeviceAddressRegionKHR region(
            MemoryStack stack,
            long address,
            long stride) {
        return VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(address)
                .stride(stride)
                .size(stride);
    }

    private static int alignUp(int value, int alignment) {
        return Math.toIntExact(alignUp((long) value, alignment));
    }

    private static long alignUp(long value, long alignment) {
        if (alignment <= 0L || (alignment & (alignment - 1L)) != 0L) {
            throw new IllegalArgumentException("Alignment must be a positive power of two");
        }
        return Math.addExact(value, alignment - 1L) & -alignment;
    }

    private static void zero(ByteBuffer bytes) {
        ByteBuffer target = bytes.duplicate();
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
            throw new IllegalStateException(
                    operation + " failed with Vulkan result " + result);
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Ray-tracing test runner is closed");
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.testDevice.close();
    }

    private record RtProperties(
            int handleSize,
            int handleAlignment,
            int baseAlignment,
            int scratchAlignment) {
        private RtProperties {
            if (handleSize <= 0
                    || handleAlignment <= 0
                    || baseAlignment <= 0
                    || scratchAlignment <= 0) {
                throw new IllegalStateException("Ray-tracing device reported invalid alignments");
            }
        }
    }

    private record AccelerationBuild(
            TestAccelerationStructure structure,
            MappedBuffer scratch) implements AutoCloseable {
        @Override
        public void close() {
            this.scratch.close();
            this.structure.close();
        }
    }

    private static final class TestAccelerationStructure implements AutoCloseable {
        private final VkDevice device;
        private final long handle;
        private final long deviceAddress;
        private final MappedBuffer backing;
        private boolean closed;

        private TestAccelerationStructure(
                VkDevice device,
                long handle,
                long deviceAddress,
                MappedBuffer backing) {
            this.device = device;
            this.handle = handle;
            this.deviceAddress = deviceAddress;
            this.backing = backing;
        }

        long handle() {
            return this.handle;
        }

        long deviceAddress() {
            return this.deviceAddress;
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(
                        this.device, this.handle, null);
                this.backing.close();
            }
        }
    }

    private static final class MappedBuffer implements AutoCloseable {
        private final VkDevice device;
        private final long buffer;
        private final long memory;
        private final ByteBuffer bytes;
        private final long size;
        private final long deviceAddress;
        private boolean closed;

        private MappedBuffer(
                VkDevice device,
                long buffer,
                long memory,
                ByteBuffer bytes,
                long size,
                long deviceAddress) {
            this.device = device;
            this.buffer = buffer;
            this.memory = memory;
            this.bytes = bytes;
            this.size = size;
            this.deviceAddress = deviceAddress;
        }

        long buffer() {
            return this.buffer;
        }

        ByteBuffer bytes() {
            return this.bytes;
        }

        long size() {
            return this.size;
        }

        long deviceAddress() {
            if (this.deviceAddress == 0L) {
                throw new IllegalStateException("Ray-test buffer has no device address");
            }
            return this.deviceAddress;
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                VK12.vkUnmapMemory(this.device, this.memory);
                VK12.vkDestroyBuffer(this.device, this.buffer, null);
                VK12.vkFreeMemory(this.device, this.memory, null);
            }
        }
    }

    private static final class TraceResources implements AutoCloseable {
        private final VkDevice device;
        private final long setLayout;
        private final long pipelineLayout;
        private final long pipeline;
        private final long descriptorPool;
        private final long descriptorSet;
        private boolean closed;

        private TraceResources(
                VkDevice device,
                long setLayout,
                long pipelineLayout,
                long pipeline,
                long descriptorPool,
                long descriptorSet) {
            this.device = device;
            this.setLayout = setLayout;
            this.pipelineLayout = pipelineLayout;
            this.pipeline = pipeline;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
        }

        long pipelineLayout() {
            return this.pipelineLayout;
        }

        long pipeline() {
            return this.pipeline;
        }

        long descriptorSet() {
            return this.descriptorSet;
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                VK12.vkDestroyDescriptorPool(this.device, this.descriptorPool, null);
                VK12.vkDestroyPipeline(this.device, this.pipeline, null);
                VK12.vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
                VK12.vkDestroyDescriptorSetLayout(this.device, this.setLayout, null);
            }
        }
    }
}
