package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/** Real-device oracle: arbitrary f32 triangles, three geometry partitions, translated instances,
 * both hit stages, freed build inputs, and a compacted BLAS. Validation errors fail the test. */
@Tag("gpu-shader")
final class PositionFetchGpuTest {
    @Test void fetchedVerticesSurviveInputRetirementAndCompaction() throws Exception {
        try (var device = VulkanTestDevice.openRayTracing(); var gpu = new Harness(device)) {
            Buffer vertices = gpu.buffer(9L * 36L,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
            int[] expected = new int[81];
            for (int i = 0; i < 9; i++) {
                float x = i * 4.0F;
                float z = i * 0.0625F;
                float[] triangle = {x, -0.25F, z, x + 2F, 0.5F, z + 0.25F,
                        x + 0.125F, 2F, z - 0.125F};
                for (int j = 0; j < 9; j++) {
                    expected[i * 9 + j] = Float.floatToRawIntBits(triangle[j]);
                    vertices.bytes.putFloat((i * 9 + j) * 4, triangle[j]);
                }
            }
            As source = gpu.build(false, vertices.address);
            vertices.close();
            gpu.traceAndCheck(source, expected);
            As compacted = gpu.compact(source);
            source.close();
            gpu.traceAndCheck(compacted, expected);
        }
    }

    private static final class Harness implements AutoCloseable {
        final VulkanTestDevice owner;
        final VkDevice device;
        final ArrayDeque<AutoCloseable> resources = new ArrayDeque<>();
        final int scratchAlignment;
        final int handleSize;
        final int handleAlignment;
        final int baseAlignment;

        Harness(VulkanTestDevice owner) {
            this.owner = owner;
            this.device = owner.device();
            try (var stack = MemoryStack.stackPush()) {
                var acceleration = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
                var ray = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
                acceleration.pNext(ray.address());
                vkGetPhysicalDeviceProperties2(owner.physicalDevice(),
                        VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(acceleration.address()));
                this.scratchAlignment = acceleration.minAccelerationStructureScratchOffsetAlignment();
                this.handleSize = ray.shaderGroupHandleSize();
                this.handleAlignment = ray.shaderGroupHandleAlignment();
                this.baseAlignment = ray.shaderGroupBaseAlignment();
            }
        }

        Buffer buffer(long size, int usage) {
            Buffer buffer = new Buffer(this.owner, size, usage);
            this.resources.push(buffer);
            return buffer;
        }

        As acceleration(long size, int type) {
            Buffer buffer = buffer(size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR);
            try (var stack = MemoryStack.stackPush()) {
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateAccelerationStructureKHR(this.device,
                        VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                                .buffer(buffer.handle).size(size).type(type), null, handle));
                As result = new As(this.device, handle.get(0), buffer);
                this.resources.push(result);
                return result;
            }
        }

        As build(boolean top, long input) {
            try (var stack = MemoryStack.stackPush()) {
                int count = top ? 1 : 3;
                var geometry = VkAccelerationStructureGeometryKHR.calloc(count, stack);
                var ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(count, stack);
                for (int i = 0; i < count; i++) {
                    var value = geometry.get(i).sType$Default();
                    if (top) {
                        value.geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
                        value.geometry().instances().sType$Default().data().deviceAddress(input);
                    } else {
                        value.geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR);
                        var triangles = value.geometry().triangles().sType$Default()
                                .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).vertexStride(12)
                                .maxVertex(8).indexType(VK_INDEX_TYPE_NONE_KHR);
                        triangles.vertexData().deviceAddress(input + i * 108L);
                    }
                    ranges.get(i).primitiveCount(top ? 1 : 3);
                }
                var info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
                int flags = top ? 0 : VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                        | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR
                        | KHRRayTracingPositionFetch.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR;
                info.get(0).sType$Default().type(top ? VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR
                                : VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                        .flags(flags).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(count).pGeometries(geometry);
                var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                vkGetAccelerationStructureBuildSizesKHR(this.device,
                        VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0),
                        top ? stack.ints(1) : stack.ints(3, 3, 3), sizes);
                As result = acceleration(sizes.accelerationStructureSize(), info.get(0).type());
                Buffer scratch = buffer(sizes.buildScratchSize() + this.scratchAlignment,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                info.get(0).dstAccelerationStructure(result.handle).scratchData()
                        .deviceAddress(align(scratch.address, this.scratchAlignment));
                execute(command -> {
                    barrier(command, VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_WRITE_BIT,
                            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                            VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                    vkCmdBuildAccelerationStructuresKHR(command, info, stack.pointers(ranges.address()));
                    barrier(command, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                            VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT);
                });
                scratch.close();
                return result;
            }
        }

        As compact(As source) {
            try (var stack = MemoryStack.stackPush()) {
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateQueryPool(this.device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                        .queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR).queryCount(1),
                        null, handle));
                long query = handle.get(0);
                try {
                    execute(command -> {
                        vkCmdResetQueryPool(command, query, 0, 1);
                        vkCmdWriteAccelerationStructuresPropertiesKHR(command, stack.longs(source.handle),
                                VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR, query, 0);
                    });
                    LongBuffer size = stack.mallocLong(1);
                    check(vkGetQueryPoolResults(this.device, query, 0, 1, size, 8,
                            VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT));
                    assertTrue(size.get(0) > 0L);
                    As result = acceleration(size.get(0), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                    var copy = VkCopyAccelerationStructureInfoKHR.calloc(stack).sType$Default()
                            .src(source.handle).dst(result.handle).mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR);
                    execute(command -> {
                        vkCmdCopyAccelerationStructureKHR(command, copy);
                        barrier(command, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                                VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT);
                    });
                    return result;
                } finally { vkDestroyQueryPool(this.device, query, null); }
            }
        }

        void traceAndCheck(As blas, int[] expected) throws Exception {
            Buffer instances = buffer(64, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
            // Matrix is row-major, with translation to distinguish object-space from world-space fetch.
            float[] transform = {1,0,0,11, 0,1,0,-3, 0,0,1,2};
            for (int i = 0; i < 12; i++) instances.bytes.putFloat(i * 4, transform[i]);
            instances.bytes.putInt(48, 0xff000000);
            instances.bytes.putInt(52, VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
            try (var stack = MemoryStack.stackPush()) {
                long address = vkGetAccelerationStructureDeviceAddressKHR(this.device,
                        VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default()
                                .accelerationStructure(blas.handle));
                instances.bytes.putLong(56, address);
            }
            As tlas = build(true, instances.address);
            instances.close();
            Buffer keys = buffer(6 * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            Buffer direct = buffer(6 * 32, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            Buffer surfaces = buffer(2 * 32, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            Buffer relation = buffer(28, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            for (int i = 0; i < 6; i++) {
                int key = 1 - (i & 1);
                keys.bytes.putInt(i * 4, key);
                for (int j = 0; j < 8; j++) {
                    int word = 0x87654321 + key * 73 + j;
                    direct.bytes.putInt(i * 32 + j * 4, word);
                    surfaces.bytes.putInt(key * 32 + j * 4, word);
                }
            }
            Buffer output = buffer(9 * 30 * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            relation.bytes.putInt(0, 0x80000002);
            int[] relationWords = {0, 1, 2, 4, 6, 7};
            for (int i = 0; i < relationWords.length; i++) {
                relation.bytes.putInt((i + 1) * 4, direct.bytes.getInt(5 * 32 + relationWords[i] * 4));
            }
            try (var stack = MemoryStack.stackPush()) {
                int hitStages = VK_SHADER_STAGE_ANY_HIT_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
                var bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
                bindings.get(0).binding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                bindings.get(1).binding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .stageFlags(hitStages | VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                bindings.get(2).binding(ShaderAbi.DESCRIPTOR_SURFACE_RECORDS).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).stageFlags(hitStages);
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateDescriptorSetLayout(this.device,
                        VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle));
                long descriptorLayout = handle.get(0);
                this.resources.push(() -> vkDestroyDescriptorSetLayout(this.device, descriptorLayout, null));
                check(vkCreatePipelineLayout(this.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                        .pSetLayouts(stack.longs(descriptorLayout))
                        .pPushConstantRanges(VkPushConstantRange.calloc(1, stack).stageFlags(hitStages).size(24)), null, handle));
                long layout = handle.get(0);
                this.resources.push(() -> vkDestroyPipelineLayout(this.device, layout, null));
                var poolSizes = VkDescriptorPoolSize.calloc(2, stack);
                poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
                check(vkCreateDescriptorPool(this.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                        .maxSets(1).pPoolSizes(poolSizes), null, handle));
                long pool = handle.get(0);
                this.resources.push(() -> vkDestroyDescriptorPool(this.device, pool, null));
                check(vkAllocateDescriptorSets(this.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(pool).pSetLayouts(stack.longs(descriptorLayout)), handle));
                long set = handle.get(0);
                var acceleration = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default()
                        .pAccelerationStructures(stack.longs(tlas.handle));
                var writes = VkWriteDescriptorSet.calloc(3, stack);
                writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).pNext(acceleration.address());
                writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(output.handle).range(output.bytes.capacity()));
                writes.get(2).sType$Default().dstSet(set).dstBinding(ShaderAbi.DESCRIPTOR_SURFACE_RECORDS)
                        .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(surfaces.handle).range(64));
                vkUpdateDescriptorSets(this.device, writes, null);
                var stages = VkPipelineShaderStageCreateInfo.calloc(4, stack);
                String[] suffixes = {"rgen", "rmiss", "rahit", "rchit"};
                int[] stageBits = {VK_SHADER_STAGE_RAYGEN_BIT_KHR, VK_SHADER_STAGE_MISS_BIT_KHR,
                        VK_SHADER_STAGE_ANY_HIT_BIT_KHR, VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR};
                for (int i = 0; i < 4; i++) {
                    stages.get(i).sType$Default().stage(stageBits[i]).pName(stack.UTF8("main"))
                            .module(shader("position_fetch." + suffixes[i] + ".spv"));
                }
                var groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(3, stack);
                for (int i = 0; i < 3; i++) groups.get(i).sType$Default()
                        .generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(VK_SHADER_UNUSED_KHR)
                        .anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
                groups.get(0).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(0);
                groups.get(1).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(1);
                groups.get(2).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                        .anyHitShader(2).closestHitShader(3);
                var info = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
                info.get(0).sType$Default().pStages(stages).pGroups(groups).maxPipelineRayRecursionDepth(1).layout(layout);
                check(vkCreateRayTracingPipelinesKHR(this.device, 0, 0, info, null, handle));
                long pipeline = handle.get(0);
                this.resources.push(() -> vkDestroyPipeline(this.device, pipeline, null));
                int stride = (int) align(this.handleSize, this.handleAlignment);
                int region = (int) align(stride, this.baseAlignment);
                Buffer sbt = buffer(region * 3L + this.baseAlignment, VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR);
                long sbtAddress = align(sbt.address, this.baseAlignment);
                int offset = (int) (sbtAddress - sbt.address);
                ByteBuffer handles = stack.malloc(this.handleSize * 3);
                check(vkGetRayTracingShaderGroupHandlesKHR(this.device, pipeline, 0, 3, handles));
                for (int i = 0; i < 3; i++) {
                    sbt.bytes.put(offset + i * region, handles, i * this.handleSize, this.handleSize);
                }
                var raygen = VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(sbtAddress).stride(stride).size(stride);
                var miss = VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(sbtAddress + region).stride(stride).size(stride);
                var hit = VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(sbtAddress + region * 2L).stride(stride).size(stride);
                var callable = VkStridedDeviceAddressRegionKHR.calloc(stack);
                execute(command -> {
                    barrier(command, VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_WRITE_BIT,
                            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_SHADER_READ_BIT);
                    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
                    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                            layout, 0, stack.longs(set), null);
                    vkCmdPushConstants(command, layout, hitStages, 0, stack.longs(keys.address, direct.address, relation.address));
                    vkCmdTraceRaysKHR(command, raygen, miss, hit, callable, 9, 1, 1);
                    barrier(command, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_SHADER_WRITE_BIT,
                            VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
                });
                for (int i = 0; i < 9; i++) {
                    assertEquals(3, output.bytes.getInt((i * 30 + 29) * 4), "Both hit stages must run");
                    int logical = (i / 3) * 2 + Math.min(i % 3, 1);
                    int key = 1 - (logical & 1);
                    for (int j = 0; j < 8; j++) assertEquals(
                            i == 8 && j == 3 ? 0x80000000 : i == 8 && j == 5 ? 0 : 0x87654321 + key * 73 + j,
                            output.bytes.getInt((i * 30 + 20 + j) * 4));
                    assertEquals(i, output.bytes.getInt((i * 30 + 28) * 4), "Shared key preserves triangle identity");
                    for (int base : new int[] {0, 10}) {
                        assertEquals(i, output.bytes.getInt((i * 30 + base + 9) * 4));
                        for (int j = 0; j < 9; j++) assertEquals(expected[i * 9 + j],
                                output.bytes.getInt((i * 30 + base + j) * 4), "Exact object vertex " + i + ":" + j);
                    }
                }
            }
            tlas.close();
        }

        long shader(String artifact) throws Exception {
            byte[] bytes = Files.readAllBytes(Path.of(System.getProperty("prime.test.slangShaderDirectory"), artifact));
            ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
            try (var stack = MemoryStack.stackPush()) {
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateShaderModule(this.device,
                        VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle));
                long module = handle.get(0);
                this.resources.push(() -> vkDestroyShaderModule(this.device, module, null));
                return module;
            } finally { MemoryUtil.memFree(code); }
        }

        void execute(Consumer<VkCommandBuffer> record) {
            try (var stack = MemoryStack.stackPush()) {
                PointerBuffer pointer = stack.mallocPointer(1);
                check(vkAllocateCommandBuffers(this.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(this.owner.commandPool()).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer));
                VkCommandBuffer command = new VkCommandBuffer(pointer.get(0), this.device);
                try {
                    check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()));
                    record.accept(command);
                    check(vkEndCommandBuffer(command));
                    check(vkQueueSubmit(this.owner.queue(), VkSubmitInfo.calloc(stack).sType$Default()
                            .pCommandBuffers(stack.pointers(command.address())), 0));
                    check(vkQueueWaitIdle(this.owner.queue()));
                } finally { vkFreeCommandBuffers(this.device, this.owner.commandPool(), command); }
            }
        }

        @Override public void close() {
            this.owner.waitIdle();
            RuntimeException failure = null;
            while (!this.resources.isEmpty()) {
                try { this.resources.pop().close(); }
                catch (Exception exception) {
                    if (failure == null) failure = new IllegalStateException(exception); else failure.addSuppressed(exception);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static final class Buffer implements AutoCloseable {
        final VkDevice device;
        final long handle;
        final long memory;
        final long address;
        final ByteBuffer bytes;
        boolean closed;
        Buffer(VulkanTestDevice owner, long size, int usage) {
            this.device = owner.device();
            try (var stack = MemoryStack.stackPush()) {
                LongBuffer value = stack.mallocLong(1);
                check(vkCreateBuffer(this.device, VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                        .usage(usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, value));
                this.handle = value.get(0);
                var requirements = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(this.device, this.handle, requirements);
                var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
                vkGetPhysicalDeviceMemoryProperties(owner.physicalDevice(), properties);
                int type = -1;
                int flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                for (int i = 0; i < properties.memoryTypeCount(); i++) {
                    if ((requirements.memoryTypeBits() & (1 << i)) != 0
                            && (properties.memoryTypes(i).propertyFlags() & flags) == flags) { type = i; break; }
                }
                assertTrue(type >= 0, "Host-coherent test buffer memory");
                var allocationFlags = VkMemoryAllocateFlagsInfo.calloc(stack).sType$Default().flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
                check(vkAllocateMemory(this.device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .pNext(allocationFlags.address()).allocationSize(requirements.size()).memoryTypeIndex(type), null, value));
                this.memory = value.get(0);
                check(vkBindBufferMemory(this.device, this.handle, this.memory, 0));
                PointerBuffer pointer = stack.mallocPointer(1);
                check(vkMapMemory(this.device, this.memory, 0, size, 0, pointer));
                this.bytes = MemoryUtil.memByteBuffer(pointer.get(0), (int) size).order(ByteOrder.LITTLE_ENDIAN);
                MemoryUtil.memSet(pointer.get(0), 0, size);
                this.address = vkGetBufferDeviceAddress(this.device,
                        VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(this.handle));
            }
        }
        @Override public void close() {
            if (this.closed) return;
            this.closed = true;
            vkUnmapMemory(this.device, this.memory);
            vkDestroyBuffer(this.device, this.handle, null);
            vkFreeMemory(this.device, this.memory, null);
        }
    }
    private static final class As implements AutoCloseable {
        final VkDevice device; final long handle; final Buffer buffer;
        boolean closed;
        As(VkDevice device, long handle, Buffer buffer) { this.device = device; this.handle = handle; this.buffer = buffer; }
        @Override public void close() {
            if (this.closed) return;
            this.closed = true;
            vkDestroyAccelerationStructureKHR(this.device, this.handle, null);
            this.buffer.close();
        }
    }
    private static void barrier(VkCommandBuffer command, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (var stack = MemoryStack.stackPush()) {
            vkCmdPipelineBarrier(command, srcStage, dstStage, 0,
                    VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess), null, null);
        }
    }
    private static long align(long value, long alignment) { return (value + alignment - 1) & -alignment; }
    private static void check(int result) { assertEquals(VK_SUCCESS, result, "Vulkan call"); }
}
