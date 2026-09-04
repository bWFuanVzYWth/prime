package dev.prime.render.vulkan;

import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.terrain.TriangleLayout;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class TopLevelAccelerationStructure {
    private static final int BUILD_FLAGS =
            KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                    | KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR;
    private final VulkanContext context;
    private final int capacity;
    private final VulkanBuffer instances;
    private final VulkanBuffer sectionTable;
    private final VulkanBuffer scratch;
    private final AccelerationStructure accelerationStructure;
    private boolean available = true;
    private boolean destroyed;
    private int instanceCount;
    private int builtInstanceCount = -1;

    private TopLevelAccelerationStructure(
            VulkanContext context,
            int capacity,
            VulkanBuffer instances,
            VulkanBuffer sectionTable,
            VulkanBuffer scratch,
            AccelerationStructure accelerationStructure) {
        this.context = context;
        this.capacity = capacity;
        this.instances = instances;
        this.sectionTable = sectionTable;
        this.scratch = scratch;
        this.accelerationStructure = accelerationStructure;
    }

    public static TopLevelAccelerationStructure create(VulkanContext context, int requestedCapacity, String label) {
        long reportedLimit = context.capabilities().maxAccelerationStructureInstanceCount();
        long deviceLimit = Long.compareUnsigned(reportedLimit, 1L << 24) < 0
                ? reportedLimit
                : 1L << 24;
        if (requestedCapacity <= 0 || requestedCapacity > deviceLimit) {
            throw new IllegalStateException(
                    "TLAS requires "
                            + requestedCapacity
                            + " instances, but the Vulkan/device-address ABI supports "
                            + deviceLimit);
        }
        int capacity = requestedCapacity <= 64
                ? Math.toIntExact(Math.min(64L, deviceLimit))
                : nextCapacity(requestedCapacity, deviceLimit);
        VulkanBuffer instances = null;
        VulkanBuffer sectionTable = null;
        VulkanBuffer scratch = null;
        AccelerationStructure accelerationStructure = null;
        try {
            instances = context.createBuffer(
                    (long) capacity * VkAccelerationStructureInstanceKHR.SIZEOF,
                    KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    true,
                    label + " instances");
            sectionTable = context.createBuffer(
                    (long) capacity * ShaderAbi.SECTION_RECORD_SIZE,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    true,
                    label + " section table");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkAccelerationStructureGeometryKHR.Buffer geometry = tlasGeometry(stack, instances.deviceAddress());
                VkAccelerationStructureBuildGeometryInfoKHR buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                        .sType$Default()
                        .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                        .flags(BUILD_FLAGS)
                        .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                        .geometryCount(1)
                        .pGeometries(geometry);
                VkAccelerationStructureBuildSizesInfoKHR sizes =
                        VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                        context.vkDevice(),
                        KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                        buildInfo,
                        stack.ints(capacity),
                        sizes);
                accelerationStructure = AccelerationStructure.create(
                        context,
                        sizes.accelerationStructureSize(),
                        KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
                        label);
                long scratchSize = Math.max(
                                sizes.buildScratchSize(), sizes.updateScratchSize())
                        + context.capabilities().accelerationStructureScratchAlignment() - 1L;
                scratch = context.createBuffer(
                        scratchSize,
                        VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        false,
                        label + " scratch");
                return new TopLevelAccelerationStructure(
                        context, capacity, instances, sectionTable, scratch, accelerationStructure);
            }
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            failure = ResourceCleanup.destroy(accelerationStructure, failure);
            failure = ResourceCleanup.destroy(scratch, failure);
            failure = ResourceCleanup.destroy(sectionTable, failure);
            failure = ResourceCleanup.destroy(instances, failure);
            throw failure;
        }
    }

    public synchronized boolean tryAcquire() {
        if (this.destroyed || !this.available) {
            return false;
        }
        this.available = false;
        return true;
    }

    public synchronized void release() {
        if (!this.destroyed) {
            this.available = true;
        }
    }

    public boolean hasCapacity(int count) {
        return count >= 0 && count <= this.capacity;
    }

    public void populate(int count, InstancePopulator populator) {
        if (count < 0 || count > this.capacity) {
            throw new IllegalArgumentException("TLAS capacity exceeded");
        }
        this.instanceCount = count;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureInstanceKHR instance = VkAccelerationStructureInstanceKHR.calloc(stack);
            var matrix = stack.mallocFloat(12);
            InstanceWriter writer = new InstanceWriter(this, count, instance, matrix);
            populator.populate(writer);
            if (writer.index != count) {
                throw new IllegalStateException(
                        "TLAS populator wrote " + writer.index + " of " + count + " instances");
            }
        }
        this.instances.flush(0L, (long) count * VkAccelerationStructureInstanceKHR.SIZEOF);
        this.sectionTable.flush(0L, (long) count * ShaderAbi.SECTION_RECORD_SIZE);
    }

    public void recordBuild(VkCommandBuffer commandBuffer) {
        boolean update = this.builtInstanceCount == this.instanceCount;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = tlasGeometry(stack, this.instances.deviceAddress());
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            buildInfo.get(0)
                    .sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(BUILD_FLAGS)
                    .mode(update
                            ? KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
                            : KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(1)
                    .pGeometries(geometry)
                    .dstAccelerationStructure(this.accelerationStructure.handle());
            if (update) {
                buildInfo.get(0).srcAccelerationStructure(
                        this.accelerationStructure.handle());
            }
            buildInfo.get(0).scratchData().deviceAddress(VulkanContext.alignUp(
                    this.scratch.deviceAddress(),
                    this.context.capabilities().accelerationStructureScratchAlignment()));
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0)
                    .primitiveCount(this.instanceCount)
                    .primitiveOffset(0)
                    .firstVertex(0)
                    .transformOffset(0);
            PointerBuffer rangePointers = stack.mallocPointer(1).put(0, range.address());
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(commandBuffer, buildInfo, rangePointers);
        }
    }

    /** Commits the update eligibility only after command submission has succeeded. */
    public void buildSubmitted() {
        this.builtInstanceCount = this.instanceCount;
    }

    public long handle() {
        return this.accelerationStructure.handle();
    }

    public long sectionTableAddress() {
        return this.sectionTable.deviceAddress();
    }

    public synchronized void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            this.available = false;
            RuntimeException failure = ResourceCleanup.destroy(this.accelerationStructure, null);
            failure = ResourceCleanup.destroy(this.instances, failure);
            failure = ResourceCleanup.destroy(this.sectionTable, failure);
            failure = ResourceCleanup.destroy(this.scratch, failure);
            ResourceCleanup.throwIfFailed(failure);
        }
    }

    private static int nextCapacity(int requested, long limit) {
        int highest = Integer.highestOneBit(requested - 1);
        long rounded = (long) highest << 1;
        return Math.toIntExact(Math.min(rounded, limit));
    }

    private static VkAccelerationStructureGeometryKHR.Buffer tlasGeometry(MemoryStack stack, long instanceAddress) {
        VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
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

    @FunctionalInterface
    public interface InstancePopulator {
        void populate(InstanceWriter writer);
    }

    public static final class InstanceWriter {
        private final TopLevelAccelerationStructure owner;
        private final int capacity;
        private final VkAccelerationStructureInstanceKHR instance;
        private final java.nio.FloatBuffer matrix;
        private int index;

        private InstanceWriter(
                TopLevelAccelerationStructure owner,
                int capacity,
                VkAccelerationStructureInstanceKHR instance,
                java.nio.FloatBuffer matrix) {
            this.owner = owner;
            this.capacity = capacity;
            this.instance = instance;
            this.matrix = matrix;
        }

        public void writeInstanced(
                long blasAddress,
                long primitiveAddress,
                long positionAddress,
                long surfaceRelationAddress,
                long lightAddress,
                long worldLightAddress,
                long worldLightLeafAddress,
                TriangleLayout triangleLayout,
                int worldLightPath,
                int lightCount,
                int worldLightLeafCount,
                int mask,
                int instanceTint,
                float transformX,
                float transformY,
                float transformZ,
                float sectionX,
                float sectionY,
                float sectionZ) {
            if (this.index >= this.capacity) {
                throw new IllegalStateException("TLAS populator wrote too many instances");
            }
            long cutoutPrimitiveBase = triangleLayout.cutoutPrimitiveBase();
            long transmissivePrimitiveBase = triangleLayout.transmissivePrimitiveBase();
            long opaqueMacroTriangleBase = triangleLayout.opaqueMacroTriangleBase();
            long cutoutMacroTriangleBase = triangleLayout.cutoutMacroTriangleBase();
            long transmissiveMacroTriangleBase = triangleLayout.transmissiveMacroTriangleBase();
            if (!isShaderUint(cutoutPrimitiveBase)
                    || !isShaderUint(transmissivePrimitiveBase)
                    || !isShaderUint(opaqueMacroTriangleBase)
                    || !isShaderUint(cutoutMacroTriangleBase)
                    || !isShaderUint(transmissiveMacroTriangleBase)) {
                throw new IllegalArgumentException(
                        "Cluster primitive bases exceed the shader uint ABI");
            }
            if ((mask & ~0xff) != 0) {
                throw new IllegalArgumentException(
                        "TLAS visibility mask exceeds its eight-bit ABI");
            }
            this.matrix.clear();
            this.matrix
                    .put(1.0F).put(0.0F).put(0.0F).put(transformX)
                    .put(0.0F).put(1.0F).put(0.0F).put(transformY)
                    .put(0.0F).put(0.0F).put(1.0F).put(transformZ)
                    .flip();
            this.instance.transform().matrix(this.matrix);
            this.instance.instanceCustomIndex(this.index)
                    .mask(mask)
                    .instanceShaderBindingTableRecordOffset(0)
                    .flags(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                    .accelerationStructureReference(blasAddress);
            MemoryUtil.memCopy(
                    this.instance.address(),
                    this.owner.instances.mappedAddress()
                            + (long) this.index * VkAccelerationStructureInstanceKHR.SIZEOF,
                    VkAccelerationStructureInstanceKHR.SIZEOF);

            long sectionAddress = this.owner.sectionTable.mappedAddress()
                    + (long) this.index * ShaderAbi.SECTION_RECORD_SIZE;
            MemoryUtil.memPutLong(
                    sectionAddress + ShaderAbi.SECTION_PRIMITIVE_ADDRESS_OFFSET,
                    primitiveAddress);
            MemoryUtil.memPutLong(
                    sectionAddress + ShaderAbi.SECTION_POSITION_ADDRESS_OFFSET,
                    positionAddress);
            MemoryUtil.memPutLong(
                    sectionAddress + ShaderAbi.SECTION_LIGHT_ADDRESS_OFFSET,
                    lightAddress);
            MemoryUtil.memPutLong(
                    sectionAddress + ShaderAbi.SECTION_WORLD_LIGHT_ADDRESS_OFFSET,
                    worldLightAddress);
            MemoryUtil.memPutInt(
                    sectionAddress + ShaderAbi.SECTION_CUTOUT_BASE_OFFSET,
                    (int) cutoutPrimitiveBase);
            MemoryUtil.memPutInt(
                    sectionAddress + ShaderAbi.SECTION_TRANSMISSIVE_BASE_OFFSET,
                    (int) transmissivePrimitiveBase);
            MemoryUtil.memPutInt(
                    sectionAddress + ShaderAbi.SECTION_WORLD_LIGHT_PATH_OFFSET,
                    worldLightPath);
            MemoryUtil.memPutInt(
                    sectionAddress + ShaderAbi.SECTION_LIGHT_COUNT_OFFSET,
                    lightCount);
            MemoryUtil.memPutLong(
                    sectionAddress + ShaderAbi.SECTION_WORLD_LIGHT_LEAF_ADDRESS_OFFSET,
                    worldLightLeafAddress);
            MemoryUtil.memPutFloat(
                    sectionAddress + ShaderAbi.SECTION_TRANSLATION_OFFSET,
                    sectionX);
            MemoryUtil.memPutFloat(
                    sectionAddress + ShaderAbi.SECTION_TRANSLATION_OFFSET + Float.BYTES,
                    sectionY);
            MemoryUtil.memPutFloat(
                    sectionAddress + ShaderAbi.SECTION_TRANSLATION_OFFSET + 2L * Float.BYTES,
                    sectionZ);
            MemoryUtil.memPutInt(
                    sectionAddress + ShaderAbi.SECTION_WORLD_LIGHT_LEAF_COUNT_OFFSET,
                    worldLightLeafCount);
            MemoryUtil.memPutInt(
                    sectionAddress + ShaderAbi.SECTION_INSTANCE_TINT_OFFSET,
                    instanceTint);
            MemoryUtil.memPutInt(
                    sectionAddress + ShaderAbi.SECTION_OPAQUE_MACRO_TRIANGLE_BASE_OFFSET,
                    (int) opaqueMacroTriangleBase);
            MemoryUtil.memPutInt(
                    sectionAddress + ShaderAbi.SECTION_CUTOUT_MACRO_TRIANGLE_BASE_OFFSET,
                    (int) cutoutMacroTriangleBase);
            MemoryUtil.memPutInt(
                    sectionAddress + ShaderAbi.SECTION_TRANSMISSIVE_MACRO_TRIANGLE_BASE_OFFSET,
                    (int) transmissiveMacroTriangleBase);
            MemoryUtil.memPutLong(
                    sectionAddress + ShaderAbi.SECTION_SURFACE_RELATION_ADDRESS_OFFSET,
                    surfaceRelationAddress);
            this.index++;
        }

        private static boolean isShaderUint(long value) {
            return value >= 0L && value <= 0xffff_ffffL;
        }
    }
}
