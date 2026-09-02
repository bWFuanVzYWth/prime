package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.PrimeInfo;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTOpacityMicromap;
import org.lwjgl.vulkan.KHRDeferredHostOperations;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;

/** Shared construction and SBT ownership for independent Prime ray-tracing programs. */
final class TraceProgram implements Destroyable {
    static final int MISS_GROUP_COUNT = 2;
    static final int HIT_GROUP_COUNT = 6;
    static final int GEOMETRY_CLASS_COUNT = 3;
    private static final int FIXED_MODULE_COUNT = 7;
    private static final int SHADOW_ANY_HIT_MODULE_BASE = 4;
    private static final String[] FIXED_RESOURCES = {
        GeneratedShaderPrograms.resource("world_rmiss"),
        GeneratedShaderPrograms.resource("shadow_rmiss"),
        GeneratedShaderPrograms.resource("world_rchit"),
        GeneratedShaderPrograms.resource("world_rahit"),
        GeneratedShaderPrograms.resource("shadow_opaque_rahit"),
        GeneratedShaderPrograms.resource("shadow_nonopaque_rahit"),
        GeneratedShaderPrograms.resource("shadow_rchit")
    };
    private static final int RECORD_DATA_SIZE = Integer.BYTES;

    static String shadowAnyHitResource(int geometryClass) {
        return FIXED_RESOURCES[shadowAnyHitModule(geometryClass)];
    }

    private static int shadowAnyHitModule(int geometryClass) {
        java.util.Objects.checkIndex(geometryClass, GEOMETRY_CLASS_COUNT);
        return SHADOW_ANY_HIT_MODULE_BASE + (geometryClass == 0 ? 0 : 1);
    }

    private final VulkanContext context;
    final long pipeline;
    private final VulkanBuffer shaderBindingTable;
    private final int raygenGroupCount;
    private final long raygenAddress;
    final long raygenRecordStride;
    final long missAddress;
    final long hitAddress;
    final long recordStride;
    private boolean destroyed;

    private TraceProgram(
            VulkanContext context,
            long pipeline,
            VulkanBuffer shaderBindingTable,
            int raygenGroupCount,
            ShaderBindingTableLayout layout) {
        this.context = context;
        this.pipeline = pipeline;
        this.shaderBindingTable = shaderBindingTable;
        this.raygenGroupCount = raygenGroupCount;
        this.raygenAddress = shaderBindingTable.deviceAddress() + layout.raygenOffset();
        this.raygenRecordStride = layout.raygenRecordStride();
        this.missAddress = shaderBindingTable.deviceAddress() + layout.missOffset();
        this.hitAddress = shaderBindingTable.deviceAddress() + layout.hitOffset();
        this.recordStride = layout.recordStride();
    }

    static TraceProgram create(
            VulkanContext context,
            long pipelineLayout,
            RaygenSchedule raygenSchedule,
            String pipelineName,
            String sbtName) {
        java.util.Objects.requireNonNull(raygenSchedule, "raygenSchedule");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pipeline = 0L;
            VulkanBuffer sbt = null;
            try {
                pipeline = createPipeline(
                        context,
                        stack,
                        pipelineLayout,
                        raygenSchedule,
                        pipelineName);
                int handleSize = context.capabilities().shaderGroupHandleSize();
                int handleAlignment = context.capabilities().shaderGroupHandleAlignment();
                int baseAlignment = context.capabilities().shaderGroupBaseAlignment();
                int raygenGroups = raygenSchedule.groupCount();
                long bufferSize = ShaderBindingTableLayout.minimumBufferSize(
                        handleSize,
                        handleAlignment,
                        baseAlignment,
                        RECORD_DATA_SIZE,
                        raygenGroups,
                        MISS_GROUP_COUNT,
                        HIT_GROUP_COUNT);
                sbt = context.createBuffer(
                        bufferSize,
                        KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR,
                        true,
                        sbtName);
                ShaderBindingTableLayout layout = ShaderBindingTableLayout.create(
                        handleSize,
                        handleAlignment,
                        baseAlignment,
                        RECORD_DATA_SIZE,
                        raygenGroups,
                        MISS_GROUP_COUNT,
                        HIT_GROUP_COUNT,
                        sbt.deviceAddress());
                if (layout.recordStride() > context.capabilities().maxShaderGroupStride()
                        || layout.raygenRecordStride()
                                > context.capabilities().maxShaderGroupStride()) {
                    throw new IllegalStateException("Prime SBT record stride exceeds the device limit");
                }
                writeShaderBindingTable(
                        context,
                        pipeline,
                        sbt,
                        handleSize,
                        layout,
                        raygenSchedule);
                return new TraceProgram(context, pipeline, sbt, raygenGroups, layout);
            } catch (RuntimeException exception) {
                if (sbt != null) {
                    sbt.destroy();
                }
                if (pipeline != 0L) {
                    VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
                }
                throw exception;
            }
        }
    }

    long raygenAddress(int group) {
        if (group < 0 || group >= this.raygenGroupCount) {
            throw new IllegalArgumentException("Invalid Prime raygen group " + group);
        }
        return this.raygenAddress + group * this.raygenRecordStride;
    }

    private static long createPipeline(
            VulkanContext context,
            MemoryStack stack,
            long pipelineLayout,
            RaygenSchedule raygenSchedule,
            String debugName) {
        PrimeInfo.LOGGER.info("Compiling {}", debugName);
        long start = System.nanoTime();
        long[] modules = new long[raygenSchedule.moduleCount() + FIXED_MODULE_COUNT];
        long deferredOperation = 0L;
        try {
            ParallelPipelineCreation.run(
                    "ray tracing shader modules",
                    modules.length,
                    index -> {
                        String resource = index < raygenSchedule.moduleCount()
                                ? raygenSchedule.moduleResource(index)
                                : FIXED_RESOURCES[index - raygenSchedule.moduleCount()];
                        modules[index] = VulkanShaderModules.create(context, resource);
                    });
            int raygenStageCount = raygenSchedule.moduleCount();
            int missStage = raygenStageCount;
            int shadowMissStage = missStage + 1;
            int closestHitStage = missStage + 2;
            int anyHitStage = missStage + 3;
            int shadowAnyHitStageBase = missStage + SHADOW_ANY_HIT_MODULE_BASE;
            int shadowClosestHitStage = missStage + 6;
            VkPipelineShaderStageCreateInfo.Buffer stages =
                    VkPipelineShaderStageCreateInfo.calloc(
                            raygenStageCount + FIXED_MODULE_COUNT, stack);
            ByteBuffer mainName = stack.UTF8("main");
            for (int index = 0; index < stages.capacity(); index++) {
                int stageFlag;
                if (index < raygenStageCount) {
                    stageFlag = KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
                } else if (index <= shadowMissStage) {
                    stageFlag = KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR;
                } else if (index == anyHitStage
                        || index >= shadowAnyHitStageBase
                                && index < shadowAnyHitStageBase + 2) {
                    stageFlag = KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
                } else {
                    stageFlag = KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
                }
                stages.get(index)
                        .sType$Default()
                        .stage(stageFlag)
                        .module(modules[index])
                        .pName(mainName);
            }
            int raygenGroupCount = raygenSchedule.groupCount();
            int groupCount = raygenGroupCount + MISS_GROUP_COUNT + HIT_GROUP_COUNT;
            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups =
                    VkRayTracingShaderGroupCreateInfoKHR.calloc(groupCount, stack);
            for (int index = 0; index < raygenGroupCount; index++) {
                generalGroup(groups.get(index), raygenSchedule.module(index));
            }
            generalGroup(groups.get(raygenGroupCount), missStage);
            generalGroup(groups.get(raygenGroupCount + 1), shadowMissStage);
            int hitBase = raygenGroupCount + MISS_GROUP_COUNT;
            triangleGroup(
                    groups.get(hitBase),
                    closestHitStage,
                    anyHitStage);
            triangleGroup(groups.get(hitBase + 1), closestHitStage, anyHitStage);
            triangleGroup(groups.get(hitBase + 2), closestHitStage, anyHitStage);
            for (int geometryClass = 0;
                    geometryClass < GEOMETRY_CLASS_COUNT;
                    geometryClass++) {
                triangleGroup(
                        groups.get(hitBase + GEOMETRY_CLASS_COUNT + geometryClass),
                        shadowClosestHitStage,
                        missStage + shadowAnyHitModule(geometryClass));
            }

            VkRayTracingPipelineCreateInfoKHR.Buffer createInfo =
                    VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            createInfo.get(0)
                    .sType$Default()
                    .flags(context.capabilities().opacityMicromapSupported()
                            ? EXTOpacityMicromap
                                    .VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT
                            : 0)
                    .pStages(stages)
                    .pGroups(groups)
                    .maxPipelineRayRecursionDepth(1)
                    .layout(pipelineLayout);
            LongBuffer pointer = stack.mallocLong(1);
            LongBuffer deferredPointer = stack.mallocLong(1);
            VulkanContext.check(
                    KHRDeferredHostOperations.vkCreateDeferredOperationKHR(
                            context.vkDevice(), null, deferredPointer),
                    "create deferred " + debugName);
            deferredOperation = deferredPointer.get(0);
            int workerCount = 1;
            try (VulkanPipelineCache.Session cache = context.pipelineCacheSession()) {
                int result = KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR(
                        context.vkDevice(),
                        deferredOperation,
                        cache.handle(),
                        createInfo,
                        null,
                        pointer);
                if (result == KHRDeferredHostOperations.VK_OPERATION_DEFERRED_KHR) {
                    int reportedConcurrency =
                            KHRDeferredHostOperations.vkGetDeferredOperationMaxConcurrencyKHR(
                                    context.vkDevice(), deferredOperation);
                    workerCount = deferredWorkerCount(
                            reportedConcurrency, Runtime.getRuntime().availableProcessors());
                    workerCount = completeDeferredPipelineCreation(
                            context, deferredOperation, workerCount);
                } else if (result
                        != KHRDeferredHostOperations.VK_OPERATION_NOT_DEFERRED_KHR) {
                    VulkanContext.check(result, "create " + debugName);
                }
            }
            long pipeline = pointer.get(0);
            context.device().instance().debug().setObjectName(
                    context.vkDevice(),
                    VK12.VK_OBJECT_TYPE_PIPELINE,
                    pipeline,
                    debugName);
            PrimeInfo.LOGGER.info(
                    "{} compiled in {} ms using {} host thread(s)",
                    debugName,
                    (System.nanoTime() - start) / 1_000_000L,
                    workerCount);
            return pipeline;
        } finally {
            if (deferredOperation != 0L) {
                KHRDeferredHostOperations.vkDestroyDeferredOperationKHR(
                        context.vkDevice(), deferredOperation, null);
            }
            for (long module : modules) {
                if (module != 0L) {
                    VK12.vkDestroyShaderModule(context.vkDevice(), module, null);
                }
            }
        }
    }

    static int deferredWorkerCount(int reportedConcurrency, int availableProcessors) {
        long reported = Integer.toUnsignedLong(reportedConcurrency);
        return (int) Math.max(
                1L,
                Math.min(reported, Math.max(availableProcessors, 1)));
    }

    private static int completeDeferredPipelineCreation(
            VulkanContext context,
            long deferredOperation,
            int workerCount) {
        Thread[] workers = new Thread[Math.max(workerCount - 1, 0)];
        int startedWorkers = 0;
        try {
            for (int index = 0; index < workers.length; index++) {
                Thread worker = new Thread(
                        () -> joinDeferredOperation(context, deferredOperation),
                        "Prime RT compiler " + (index + 1));
                workers[index] = worker;
                worker.start();
                startedWorkers++;
            }
        } catch (RuntimeException | OutOfMemoryError exception) {
            PrimeInfo.LOGGER.warn(
                    "Started only {} of {} Prime RT compiler worker thread(s)",
                    startedWorkers,
                    workers.length,
                    exception);
        }
        joinDeferredOperation(context, deferredOperation);

        boolean interrupted = false;
        for (int index = 0; index < startedWorkers; index++) {
            Thread worker = workers[index];
            for (;;) {
                try {
                    worker.join();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        int result = KHRDeferredHostOperations.vkGetDeferredOperationResultKHR(
                context.vkDevice(), deferredOperation);
        while (result == VK12.VK_NOT_READY) {
            VulkanContext.check(
                    joinDeferredOperation(context, deferredOperation),
                    "join Prime deferred ray tracing pipeline");
            result = KHRDeferredHostOperations.vkGetDeferredOperationResultKHR(
                    context.vkDevice(), deferredOperation);
        }
        VulkanContext.check(result, "complete Prime deferred ray tracing pipeline");
        return startedWorkers + 1;
    }

    private static int joinDeferredOperation(
            VulkanContext context,
            long deferredOperation) {
        for (;;) {
            int result = KHRDeferredHostOperations.vkDeferredOperationJoinKHR(
                    context.vkDevice(), deferredOperation);
            if (result == KHRDeferredHostOperations.VK_THREAD_IDLE_KHR) {
                Thread.yield();
                continue;
            }
            if (result == VK12.VK_SUCCESS
                    || result == KHRDeferredHostOperations.VK_THREAD_DONE_KHR) {
                return VK12.VK_SUCCESS;
            }
            return result;
        }
    }

    private static void writeShaderBindingTable(
            VulkanContext context,
            long pipeline,
            VulkanBuffer sbt,
            int handleSize,
            ShaderBindingTableLayout layout,
            RaygenSchedule raygenSchedule) {
        int raygenGroupCount = raygenSchedule.groupCount();
        int groupCount = raygenGroupCount + MISS_GROUP_COUNT + HIT_GROUP_COUNT;
        ByteBuffer handles = MemoryUtil.memAlloc(groupCount * handleSize);
        try {
            VulkanContext.check(
                    KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(
                            context.vkDevice(), pipeline, 0, groupCount, handles),
                    "read Prime shader group handles");
            long source = MemoryUtil.memAddress(handles);
            long destination = sbt.mappedAddress();
            for (int index = 0; index < raygenGroupCount; index++) {
                long record = destination + layout.raygenOffset()
                        + index * layout.raygenRecordStride();
                MemoryUtil.memCopy(source + (long) index * handleSize, record, handleSize);
                MemoryUtil.memPutInt(record + handleSize, raygenSchedule.control(index));
            }
            for (int index = 0; index < MISS_GROUP_COUNT; index++) {
                MemoryUtil.memCopy(
                        source + (long) (raygenGroupCount + index) * handleSize,
                        destination + layout.missOffset() + index * layout.recordStride(),
                        handleSize);
            }
            for (int index = 0; index < HIT_GROUP_COUNT; index++) {
                MemoryUtil.memCopy(
                        source
                                + (long) (raygenGroupCount + MISS_GROUP_COUNT + index)
                                        * handleSize,
                        destination + layout.hitOffset() + index * layout.recordStride(),
                        handleSize);
            }
            sbt.flush(0L, layout.totalSize());
        } finally {
            MemoryUtil.memFree(handles);
        }
    }

    private static void generalGroup(
            VkRayTracingShaderGroupCreateInfoKHR group, int shaderIndex) {
        group.sType$Default()
                .type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                .generalShader(shaderIndex)
                .closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
    }

    private static void triangleGroup(
            VkRayTracingShaderGroupCreateInfoKHR group, int closestHit, int anyHit) {
        group.sType$Default()
                .type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                .generalShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .closestHitShader(closestHit)
                .anyHitShader(anyHit)
                .intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            this.shaderBindingTable.destroy();
            VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
        }
    }
}
