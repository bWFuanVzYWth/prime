package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;

final class TracePipelinesContractTest {
    @Test
    void commandWritesWaitForShaderAndIndirectConsumers() {
        long expectedStages =
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
        long expectedAccesses =
                VK12.VK_ACCESS_SHADER_READ_BIT
                        | VK12.VK_ACCESS_SHADER_WRITE_BIT
                        | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
        assertEquals(expectedStages, WavefrontCommands.COMMAND_WRITE_SOURCE_STAGES);
        assertEquals(expectedAccesses, WavefrontCommands.COMMAND_WRITE_SOURCE_ACCESSES);
    }

    @Test
    void realtimeAndOfflineHaveIndependentSchedulesAndDescriptors() {
        assertEquals(1, PrimaryRayTracingPipeline.DESCRIPTOR_BINDING_COUNT);
        RaygenSchedule primary = GeneratedShaderPrograms.schedule("primary");
        assertEquals(1, primary.groupCount());
        assertEquals(1, primary.moduleCount());

        assertEquals(15, RealtimeRayTracingPipeline.dispatchCount(1));
        assertEquals(19, RealtimeRayTracingPipeline.dispatchCount(2));
        assertEquals(43, RealtimeRayTracingPipeline.dispatchCount(8));
        assertThrows(
                IllegalArgumentException.class,
                () -> RealtimeRayTracingPipeline.dispatchCount(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> RealtimeRayTracingPipeline.dispatchCount(9));
        assertEquals(25, RealtimeRayTracingPipeline.DESCRIPTOR_BINDING_COUNT);

        assertEquals(49, OfflineRayTracingPipeline.dispatchCount(12));
        assertEquals(5, OfflineRayTracingPipeline.dispatchCount(1));
        assertEquals(257, OfflineRayTracingPipeline.dispatchCount(64));
        assertThrows(
                IllegalArgumentException.class,
                () -> OfflineRayTracingPipeline.dispatchCount(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> OfflineRayTracingPipeline.dispatchCount(65));
        assertEquals(3, OfflineRayTracingPipeline.DESCRIPTOR_BINDING_COUNT);

        RaygenSchedule realtime =
                GeneratedShaderPrograms.schedule("realtime.standard", ".rgen.spv");
        assertEquals(23, realtime.groupCount());
        assertEquals(16, realtime.moduleCount());
        RaygenSchedule offline = GeneratedShaderPrograms.schedule("offline", ".rgen.spv");
        assertEquals(10, offline.groupCount());
        assertEquals(6, offline.moduleCount());
    }

    @Test
    void realtimeScheduleKeepsItsDeclaredGroupsAndResources() {
        RaygenSchedule realtime =
                GeneratedShaderPrograms.schedule("realtime.standard", "_ser.rgen.spv");
        assertEquals(16, realtime.moduleCount());
        assertEquals(23, realtime.groupCount());
        assertEquals(
                "/prime/shaders/realtime_wavefront_surface_split_ser.rgen.spv",
                realtime.moduleResource(2));
        assertEquals(
                "/prime/shaders/realtime_wavefront_guide_delta_walk_ser.rgen.spv",
                realtime.moduleResource(4));
        assertEquals(
                "/prime/shaders/realtime_wavefront_fixed_direct_ser.rgen.spv",
                realtime.moduleResource(10));
        assertEquals(
                "/prime/shaders/realtime_wavefront_tail_admission_ser.rgen.spv",
                realtime.moduleResource(12));
        assertEquals(
                "/prime/shaders/realtime_wavefront_tail_ser.rgen.spv",
                realtime.moduleResource(13));
    }

    @Test
    void offlineScheduleKeepsItsFourStageGroupsAndResources() {
        RaygenSchedule offline = GeneratedShaderPrograms.schedule("offline", "_ser.rgen.spv");
        assertEquals(6, offline.moduleCount());
        assertEquals(10, offline.groupCount());
        assertEquals(
                "/prime/shaders/offline_wavefront_camera_trace_ser.rgen.spv",
                offline.moduleResource(0));
        assertEquals(
                "/prime/shaders/offline_wavefront_bridge_trace_ser.rgen.spv",
                offline.moduleResource(1));
        assertEquals(
                "/prime/shaders/offline_wavefront_light_select.rgen.spv",
                offline.moduleResource(2));
        assertEquals(
                "/prime/shaders/offline_wavefront_direct_ser.rgen.spv",
                offline.moduleResource(3));
        assertEquals(
                "/prime/shaders/offline_wavefront_scatter_ser.rgen.spv",
                offline.moduleResource(4));
        assertEquals(
                "/prime/shaders/offline_wavefront_sample_resolve.rgen.spv",
                offline.moduleResource(5));
    }

    @Test
    void raygenScheduleRejectsInvalidParallelMetadataAtItsBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RaygenSchedule.of(List.of("module"), new int[] {0}, new int[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> RaygenSchedule.of(List.of("module"), new int[] {1}, new int[] {0}));
        assertThrows(IllegalArgumentException.class, () -> RaygenSchedule.single("", 0));
    }

    @Test
    void wavefrontBackingHasDeclaredFourKSize() {
        assertEquals(1_692_057_648L, LambertRayTracingPipeline.LAYOUT.wavefrontBytes(3840, 2160));
        assertEquals(48L + 60L * 3840 * 2160, LambertRayTracingPipeline.LAYOUT.queueBytes(3840, 2160));
        assertEquals(364L, LambertRayTracingPipeline.LAYOUT.wavefrontBytes(1, 1));
        LambertRayTracingPipeline.LAYOUT.validateDispatch(3840, 2160, 3840 * 2160);
        LambertRayTracingPipeline.LAYOUT.validateRanges(3840, 2160, 0xffff_ffffL);
        assertThrows(IllegalStateException.class, () ->
                LambertRayTracingPipeline.LAYOUT.validateDispatch(3840, 2160, 3840 * 2160 - 1));
        assertEquals(1, RealtimeRayTracingPipeline.LAYOUT.pathSlotsPerPixel());
        assertEquals(3_550_003_312L, RealtimeRayTracingPipeline.LAYOUT.wavefrontBytes(3840, 2160));
        assertEquals(316L * 3840 * 2160 + 112,
                RealtimeRayTracingPipeline.LAYOUT.queueBytes(3840, 2160));
        assertEquals(2_023_833_632L, OfflineRayTracingPipeline.LAYOUT.wavefrontBytes(3840, 2160));
        assertEquals(962_150_432L, OfflineRayTracingPipeline.LAYOUT.queueBytes(3840, 2160));
        assertEquals(
                1_957_478_400L,
                OfflineRayTracingPipeline.LAYOUT.queueCommandOffset(3840, 2160));
        assertEquals(
                1930.0781555175781,
                OfflineRayTracingPipeline.LAYOUT.wavefrontBytes(3840, 2160)
                        / (1024.0 * 1024.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> OfflineRayTracingPipeline.LAYOUT.wavefrontBytes(0, 2160));
        assertThrows(
                ArithmeticException.class,
                () -> RealtimeRayTracingPipeline.LAYOUT.wavefrontBytes(
                        Integer.MAX_VALUE, Integer.MAX_VALUE));
        RealtimeRayTracingPipeline.LAYOUT.validateRanges(3840, 2160, 0xffff_ffffL);
        OfflineRayTracingPipeline.LAYOUT.validateRanges(3840, 2160, 0xffff_ffffL);
        assertThrows(
                IllegalStateException.class,
                () -> RealtimeRayTracingPipeline.LAYOUT.validateDispatch(
                        3840, 2160, 3840 * 2160));
        RealtimeRayTracingPipeline.LAYOUT.validateDispatch(3840, 2160, 2 * 3840 * 2160);
        OfflineRayTracingPipeline.LAYOUT.validateDispatch(3840, 2160, 1 << 24);
    }

    @Test
    void lambertKeepsGuideFirstAndTerminalStagesSeparateFromDeepShading() {
        RaygenSchedule lambert = GeneratedShaderPrograms.schedule("lambert");
        assertEquals(7, lambert.moduleCount());
        assertEquals(10, lambert.groupCount());
        assertNotEquals(lambert.module(GeneratedShaderPrograms.LAMBERT_FIRST),
                lambert.module(GeneratedShaderPrograms.LAMBERT_SHADE_0));
        assertNotEquals(lambert.module(GeneratedShaderPrograms.LAMBERT_TERMINAL_0),
                lambert.module(GeneratedShaderPrograms.LAMBERT_SHADE_0));
        assertEquals(lambert.module(GeneratedShaderPrograms.LAMBERT_TRACE_0),
                lambert.module(GeneratedShaderPrograms.LAMBERT_TRACE_1));
        assertEquals(lambert.module(GeneratedShaderPrograms.LAMBERT_SHADE_0),
                lambert.module(GeneratedShaderPrograms.LAMBERT_SHADE_1));
        assertEquals(0, lambert.control(GeneratedShaderPrograms.LAMBERT_TRACE_0));
        assertEquals(0, lambert.control(GeneratedShaderPrograms.LAMBERT_SHADE_0));
        assertEquals(1, lambert.control(GeneratedShaderPrograms.LAMBERT_TRACE_1));
        assertEquals(1, lambert.control(GeneratedShaderPrograms.LAMBERT_SHADE_1));
        RaygenSchedule scalar = LambertRayTracingPipeline.schedule(false);
        RaygenSchedule subgroup = LambertRayTracingPipeline.schedule(true);
        assertEquals(scalar.groupCount(), subgroup.groupCount());
        assertEquals(scalar.moduleCount(), subgroup.moduleCount());
        for (int group = 0; group < scalar.groupCount(); ++group) {
            assertEquals(scalar.control(group), subgroup.control(group));
            String resource = scalar.moduleResource(scalar.module(group));
            String expected = group == GeneratedShaderPrograms.LAMBERT_SHADE_0
                    || group == GeneratedShaderPrograms.LAMBERT_SHADE_1
                    ? GeneratedShaderPrograms.resource("lambert_shade_subgroup")
                    : group == GeneratedShaderPrograms.LAMBERT_FIRST
                            ? GeneratedShaderPrograms.resource("lambert_first_subgroup")
                            : group == GeneratedShaderPrograms.LAMBERT_CAMERA
                                    ? GeneratedShaderPrograms.resource("lambert_camera_subgroup") : resource;
            assertEquals(expected, subgroup.moduleResource(subgroup.module(group)));
        }
    }

    @Test
    void lambertConfiguredBudgetOwnsSchedulingAndPreservesMinimumPriority() {
        assertEquals(1, LambertRayTracingPipeline.bounceLimit(1, 1));
        assertEquals(6, LambertRayTracingPipeline.dispatchCount(1, 1));
        assertEquals(16, LambertRayTracingPipeline.bounceLimit(2, 16));
        assertEquals(36, LambertRayTracingPipeline.dispatchCount(2, 16));
        assertEquals(8, LambertRayTracingPipeline.bounceLimit(8, 1));
        assertEquals(132, LambertRayTracingPipeline.dispatchCount(2, 64));
        assertEquals(0x1002, LambertRayTracingPipeline.queueMetadata(2, 16));
        assertEquals(0x4008, LambertRayTracingPipeline.queueMetadata(8, 64));
        assertThrows(IllegalArgumentException.class, () -> LambertRayTracingPipeline.bounceLimit(0, 16));
        assertThrows(IllegalArgumentException.class, () -> LambertRayTracingPipeline.bounceLimit(9, 16));
        assertThrows(IllegalArgumentException.class, () -> LambertRayTracingPipeline.bounceLimit(2, 65));
        assertThrows(IllegalArgumentException.class, () -> LambertRayTracingPipeline.queueMetadata(2, 0));
    }

    @Test
    void deferredCompilationClampsDriverConcurrencyToTheHost() {
        assertEquals(1, TraceProgram.deferredWorkerCount(0, 32));
        assertEquals(2, TraceProgram.deferredWorkerCount(2, 32));
        assertEquals(8, TraceProgram.deferredWorkerCount(32, 8));
        assertEquals(32, TraceProgram.deferredWorkerCount(-1, 32));
        assertEquals(1, TraceProgram.deferredWorkerCount(8, 0));
    }

    @Test
    void shadowHitGroupsUseOpaqueAndNonOpaqueAnyHitPrograms() {
        assertEquals(3, TraceProgram.GEOMETRY_CLASS_COUNT);
        assertEquals(
                GeneratedShaderPrograms.resource("shadow_opaque_rahit"),
                TraceProgram.shadowAnyHitResource(0));
        assertEquals(
                GeneratedShaderPrograms.resource("shadow_nonopaque_rahit"),
                TraceProgram.shadowAnyHitResource(1));
        assertEquals(
                GeneratedShaderPrograms.resource("shadow_nonopaque_rahit"),
                TraceProgram.shadowAnyHitResource(2));
        assertThrows(IndexOutOfBoundsException.class, () -> TraceProgram.shadowAnyHitResource(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> TraceProgram.shadowAnyHitResource(3));
    }
}
