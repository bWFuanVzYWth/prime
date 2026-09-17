// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void executionVariantsPreserveLogicalGroupsAndQueueControls() {
        for (String topology : List.of("realtime.standard", "offline")) {
            RaygenSchedule scalar = GeneratedShaderPrograms.schedule(topology, ".rgen.spv");
            RaygenSchedule ser = GeneratedShaderPrograms.schedule(topology, "_ser.rgen.spv");
            assertEquals(scalar.groupCount(), ser.groupCount());
            for (int group = 0; group < scalar.groupCount(); ++group) {
                assertEquals(scalar.control(group), ser.control(group));
                assertTrue(scalar.module(group) >= 0 && scalar.module(group) < scalar.moduleCount());
                assertTrue(ser.module(group) >= 0 && ser.module(group) < ser.moduleCount());
                assertTrue(!scalar.moduleResource(scalar.module(group)).isBlank());
                assertTrue(!ser.moduleResource(ser.module(group)).isBlank());
            }
        }
    }

    @Test
    void scheduleOwnsItsInputsAndAllowsSharedModulesWithIndependentControls() {
        var modules = new java.util.ArrayList<>(List.of("trace", "scatter"));
        int[] groups = {0, 1, 0, 1};
        int[] controls = {0, 0, 1, 1};
        RaygenSchedule schedule = RaygenSchedule.of(modules, groups, controls);
        modules.set(0, "mutated");
        groups[0] = 1;
        controls[0] = 9;
        assertEquals("trace", schedule.moduleResource(schedule.module(0)));
        assertEquals(0, schedule.control(0));
        assertEquals(schedule.module(0), schedule.module(2));
        assertNotEquals(schedule.control(0), schedule.control(2));
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
    void wavefrontRegionsAreDisjointAndRespectDeviceBoundaries() {
        for (WavefrontLayout layout : List.of(LambertRayTracingPipeline.LAYOUT,
                RealtimeRayTracingPipeline.LAYOUT, OfflineRayTracingPipeline.LAYOUT)) {
            for (int[] extent : new int[][] {{1, 1}, {17, 31}, {1920, 1080}, {3840, 2160}}) {
                int width = extent[0], height = extent[1];
                long pixels = (long) width * height;
                long pathEnd = pixels * layout.pathSlotsPerPixel() * layout.pathRecordSize();
                long queueBegin = layout.queueOffset(width, height);
                long commandBegin = layout.queueCommandOffset(width, height);
                long commandEnd = commandBegin + (long) layout.queueCount() * layout.commandStride();
                long end = layout.wavefrontBytes(width, height);
                assertTrue(pathEnd <= queueBegin);
                assertEquals(0L, queueBegin % 256L); // Vulkan descriptor suballocation alignment.
                assertTrue(commandBegin >= queueBegin + pixels * layout.scratchRecordSize());
                assertTrue(commandEnd <= end);
                assertEquals(pixels * layout.queueStorageEntriesPerPixel() * layout.indexSize(), end - commandEnd);
                long requiredRange = Math.max(queueBegin, layout.queueBytes(width, height));
                layout.validateRanges(width, height, requiredRange);
                assertThrows(IllegalStateException.class, () -> layout.validateRanges(width, height, requiredRange - 1));
                int invocations = Math.toIntExact(pixels * layout.queueEntriesPerPixel());
                layout.validateDispatch(width, height, invocations);
                assertThrows(IllegalStateException.class, () -> layout.validateDispatch(width, height, invocations - 1));
            }
            assertThrows(IllegalArgumentException.class, () -> layout.wavefrontBytes(0, 16));
            assertThrows(ArithmeticException.class, () -> layout.wavefrontBytes(Integer.MAX_VALUE, Integer.MAX_VALUE));
        }
    }

    @Test
    void lambertKeepsGuideFirstAndTerminalStagesSeparateFromDeepShading() {
        RaygenSchedule lambert = GeneratedShaderPrograms.schedule("lambert");
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
        RaygenSchedule scalar = LambertRayTracingPipeline.schedule(false, false);
        RaygenSchedule subgroup = LambertRayTracingPipeline.schedule(true, false);
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
    void lambertSerUsesNarrowAnyHitAndRetainsSubgroupShading() {
        RaygenSchedule ser = LambertRayTracingPipeline.schedule(true, true);
        RaygenSchedule scalar = LambertRayTracingPipeline.schedule(false, true);
        RaygenSchedule unaccelerated = LambertRayTracingPipeline.schedule(false, false);
        assertEquals(scalar.groupCount(), ser.groupCount());
        for (int group = 0; group < ser.groupCount(); ++group) {
            assertEquals(scalar.control(group), ser.control(group));
            assertEquals(unaccelerated.moduleResource(unaccelerated.module(group)),
                    scalar.moduleResource(scalar.module(group)));
        }
        RaygenSchedule subgroup = LambertRayTracingPipeline.schedule(true, false);
        for (int group : new int[] {GeneratedShaderPrograms.LAMBERT_FIRST,
                GeneratedShaderPrograms.LAMBERT_SHADE_0, GeneratedShaderPrograms.LAMBERT_SHADE_1}) {
            assertEquals(subgroup.moduleResource(subgroup.module(group)),
                    ser.moduleResource(ser.module(group)));
        }
        String[] normal = LambertRayTracingPipeline.fixedResources(false);
        String[] reordered = LambertRayTracingPipeline.fixedResources(true);
        assertEquals(normal.length, reordered.length);
        for (int i = 0; i < normal.length; ++i) assertEquals(i == 3
                ? GeneratedShaderPrograms.resource("lambert_world_rahit_ser") : normal[i], reordered[i]);
    }

    @Test
    void lambertConfiguredBudgetOwnsSchedulingAndPreservesMinimumPriority() {
        assertEquals(1, LambertRayTracingPipeline.bounceLimit(1, 1));
        assertEquals(16, LambertRayTracingPipeline.bounceLimit(2, 16));
        assertEquals(8, LambertRayTracingPipeline.bounceLimit(8, 1));
        assertEquals(0x1002, LambertRayTracingPipeline.queueMetadata(2, 16));
        assertEquals(0x4008, LambertRayTracingPipeline.queueMetadata(8, 64));
        assertThrows(IllegalArgumentException.class, () -> LambertRayTracingPipeline.bounceLimit(0, 16));
        assertThrows(IllegalArgumentException.class, () -> LambertRayTracingPipeline.bounceLimit(9, 16));
        assertThrows(IllegalArgumentException.class, () -> LambertRayTracingPipeline.bounceLimit(2, 65));
        assertThrows(IllegalArgumentException.class, () -> LambertRayTracingPipeline.queueMetadata(2, 0));
    }

    @Test
    void realtimeMinimumAndDeltaShareOnlyTheReservedCommandWord() {
        for (int minimum = 1; minimum <= 8; ++minimum) {
            for (int delta = 1; delta <= 64; ++delta) {
                int metadata = RealtimeRayTracingPipeline.queueMetadata(minimum, delta);
                assertEquals(delta, metadata & 255);
                assertEquals(minimum, metadata >>> 8 & 255);
                assertEquals(0, metadata >>> 16);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> RealtimeRayTracingPipeline.queueMetadata(0, 12));
        assertThrows(IllegalArgumentException.class, () -> RealtimeRayTracingPipeline.queueMetadata(2, 65));
        assertThrows(IllegalArgumentException.class, () -> RealtimeRayTracingPipeline.dispatchCount(2, 65));
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
