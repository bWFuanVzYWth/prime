package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.shader.ShaderAbi;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;

final class TracePipelinesContractTest {
    private static final int OP_TYPE_INT = 21;
    private static final int OP_TYPE_FLOAT = 22;
    private static final int OP_TYPE_VECTOR = 23;
    private static final int OP_TYPE_ARRAY = 28;
    private static final int OP_TYPE_RUNTIME_ARRAY = 29;
    private static final int OP_TYPE_STRUCT = 30;
    private static final int OP_TYPE_POINTER = 32;
    private static final int OP_VARIABLE = 59;
    private static final int OP_CONSTANT = 43;
    private static final int OP_DECORATE = 71;
    private static final int OP_MEMBER_DECORATE = 72;
    private static final int OP_GROUP_NON_UNIFORM_ELECT = 333;
    private static final int OP_GROUP_NON_UNIFORM_BROADCAST_FIRST = 338;
    private static final int OP_GROUP_NON_UNIFORM_BALLOT = 339;
    private static final int OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT = 342;
    private static final int OP_TRACE_RAY_KHR = 4445;
    private static final int DECORATION_ARRAY_STRIDE = 6;
    private static final int DECORATION_BINDING = 33;
    private static final int DECORATION_DESCRIPTOR_SET = 34;
    private static final int DECORATION_OFFSET = 35;

    private static final int STORAGE_BUFFER = 12;
    private static final int STORAGE_RAY_PAYLOAD = 5338;
    private static final int STORAGE_INCOMING_RAY_PAYLOAD = 5342;

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
        assertEquals(23, RealtimeRayTracingPipeline.RAYGEN_GROUP_COUNT);
        assertEquals(16, RealtimeRayTracingPipeline.RAYGEN_MODULE_COUNT);
        assertEquals(14, RealtimeRayTracingPipeline.dispatchCount(1));
        assertEquals(18, RealtimeRayTracingPipeline.dispatchCount(2));
        assertEquals(42, RealtimeRayTracingPipeline.dispatchCount(8));
        assertThrows(
                IllegalArgumentException.class,
                () -> RealtimeRayTracingPipeline.dispatchCount(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> RealtimeRayTracingPipeline.dispatchCount(9));
        assertEquals(25, RealtimeRayTracingPipeline.DESCRIPTOR_BINDING_COUNT);

        assertEquals(10, OfflineRayTracingPipeline.RAYGEN_GROUP_COUNT);
        assertEquals(6, OfflineRayTracingPipeline.RAYGEN_MODULE_COUNT);
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

        assertEquals(List.of(
                        0, 1, 2, 3, 4, 3, 4, 5, 6, 7,
                        8, 9, 10, 11, 8, 9, 10, 11,
                        12, 12, 13, 14, 15),
                java.util.stream.IntStream
                .range(0, RealtimeRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(RealtimeRayTracingPipeline::raygenModule)
                .boxed()
                .toList());
        assertEquals(List.of(
                        0, 0, 0, 0, 0, 1, 0, 0, 0, 0,
                        0, 0, 0, 0, 1, 1, 1, 1,
                        0, 1, 0, 0, 0),
                java.util.stream.IntStream
                .range(0, RealtimeRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(RealtimeRayTracingPipeline::raygenControl)
                .boxed()
                .toList());
        assertEquals(List.of(0, 1, 2, 3, 4, 1, 2, 3, 4, 5), java.util.stream.IntStream
                .range(0, OfflineRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(OfflineRayTracingPipeline::raygenModule)
                .boxed()
                .toList());
        assertEquals(List.of(0, 0, 0, 0, 0, 1, 1, 1, 1, 0), java.util.stream.IntStream
                .range(0, OfflineRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(OfflineRayTracingPipeline::raygenControl)
                .boxed()
                .toList());
    }

    @Test
    void realtimeBarriersExposeOnlyTheNextStageImageDependencies() {
        assertArrayEquals(
                new int[] {1, 2},
                RealtimeRayTracingPipeline.primaryDirectInputImageIndices());
        assertArrayEquals(
                new int[] {0, 1, 2, 4, 6, 7, 8, 9, 10, 20, 21},
                RealtimeRayTracingPipeline.primaryInputImageIndices());
        assertArrayEquals(
                new int[] {
                    0, 1, 2, 4, 5, 6, 7, 8, 9, 10,
                    11, 12, 13, 14, 15, 16, 17, 18, 21
                },
                RealtimeRayTracingPipeline.nextStepInputImageIndices());
        assertTrue(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimePrimaryGroups.DELTA_WALK_0));
        assertTrue(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimePrimaryGroups.GUIDE_DELTA_WALK_0));
        assertTrue(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimePrimaryGroups.LANDING_DIRECT));
        assertTrue(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimePrimaryGroups.LANDING_SCATTER));
        assertTrue(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimeStandardGroups.DIRECT_0));
        assertTrue(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimeStandardGroups.SCATTER_1));
        assertFalse(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimePrimaryGroups.LANDING_LIGHT_SELECT));
        assertFalse(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimeStandardGroups.LIGHT_SELECT_0));
        assertFalse(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimeStandardGroups.BRIDGE_TRACE_1));
    }

    @Test
    void realtimeScheduleKeepsItsDeclaredGroupsAndResources() {
        RaygenSchedule realtime = RealtimeStandardGroups.standardSchedule("_ser.rgen.spv");
        assertEquals(RealtimeStandardGroups.MODULE_COUNT, realtime.moduleCount());
        assertEquals(RealtimeStandardGroups.GROUP_COUNT, realtime.groupCount());
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
        assertEquals(14, RealtimeRayTracingPipeline.dispatchCount(1));
        assertEquals(42, RealtimeRayTracingPipeline.dispatchCount(8));
    }

    @Test
    void offlineScheduleKeepsItsFourStageGroupsAndResources() {
        RaygenSchedule offline = OfflineGroups.schedule("_ser.rgen.spv");
        assertEquals(OfflineGroups.MODULE_COUNT, offline.moduleCount());
        assertEquals(OfflineGroups.GROUP_COUNT, offline.groupCount());
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

    static void realtimeTailAdmissionSeesOnlyCompactPathAndQueueStorage() throws IOException {
        Set<Integer> expected = Set.of(
                ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE);
        for (String suffix : List.of("", "_ser")) {
            assertEquals(
                    expected,
                    descriptorBindings(
                            List.of(wavefrontShader(
                                    "realtime", "tail_admission", suffix)),
                            1));
        }
    }

    @Test
    void raygenScheduleRejectsInvalidParallelMetadataAtItsBoundary() {
        assertThrows(IllegalArgumentException.class, () -> RaygenSchedule.of(
                List.of("module"), new int[] {0}, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> RaygenSchedule.of(
                List.of("module"), new int[] {1}, new int[] {0}));
        assertThrows(IllegalArgumentException.class, () -> RaygenSchedule.single("", 0));
    }

    static void imageDiagnosticsUseOneIsolatedSourceAndTargetLayout() throws IOException {
        assertEquals(
                Set.of(0, 1),
                descriptorBindings(List.of("image_diagnostic_rgba8.comp.spv"), 0));
        assertEquals(
                Set.of(0, 1),
                descriptorBindings(List.of("image_diagnostic_rgba16.comp.spv"), 0));
    }

    static void streamlineInputPreparationHasOneNarrowDescriptorLayout() throws IOException {
        assertEquals(
                Set.of(0, 1, 2, 3, 4),
                descriptorBindings(List.of("streamline_input.comp.spv"), 0));
    }

    static void setOneAbiDoesNotCrossRendererBoundary() throws IOException {
        for (String suffix : List.of("", "_ser")) {
            Set<Integer> realtime = descriptorBindings(
                    List.of(
                            wavefrontShader("realtime", "camera_trace", suffix),
                            wavefrontShader("realtime", "surface_split", suffix),
                            wavefrontShader("realtime", "delta_walk", suffix),
                            wavefrontShader("realtime", "guide_delta_walk", suffix),
                            wavefrontShader("realtime", "landing_light_select", suffix),
                            wavefrontShader("realtime", "landing_direct", suffix),
                            wavefrontShader("realtime", "landing_scatter", suffix),
                            wavefrontShader("realtime", "fixed_bridge_trace", suffix),
                            wavefrontShader("realtime", "fixed_light_select", suffix),
                            wavefrontShader("realtime", "fixed_direct", suffix),
                            wavefrontShader("realtime", "fixed_scatter", suffix),
                            wavefrontShader("realtime", "tail_admission", suffix),
                            wavefrontShader("realtime", "tail", suffix),
                            wavefrontShader(
                                    "realtime", "branch_resolve", suffix),
                            wavefrontShader("realtime", "visible_direct", suffix),
                            wavefrontShader("realtime", "noisy_output_resolve", suffix)),
                    1);
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS));
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE));
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_STABLE_RADIANCE));
            assertFalse(realtime.contains(ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN));
            assertFalse(realtime.contains(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS));
            assertFalse(realtime.contains(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE));

            Set<Integer> offline = descriptorBindings(
                    wavefrontShaders(
                            "offline",
                            suffix,
                            List.of(
                                    "camera_trace",
                                    "bridge_trace",
                                    "light_select",
                                    "direct",
                                    "scatter",
                                    "sample_resolve")),
                    1);
            assertEquals(Set.of(
                    ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN,
                    ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE), offline);
        }
    }

    static void realtimeStbnDoesNotEnterTheOfflineShaderClosure() throws IOException {
        for (String suffix : List.of("", "_ser")) {
            Set<Integer> realtime = descriptorBindings(
                    List.of(wavefrontShader("realtime", "fixed_direct", suffix)),
                    0);
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_REALTIME_STBN));
            Set<Integer> offline = descriptorBindings(
                    wavefrontShaders(
                            "offline",
                            suffix,
                            List.of(
                                    "camera_trace",
                                    "bridge_trace",
                                    "light_select",
                                    "direct",
                                    "scatter",
                                    "sample_resolve")),
                    0);
            assertFalse(offline.contains(ShaderAbi.DESCRIPTOR_REALTIME_STBN));
        }
    }

    static void canonicalBaseColorDescriptorUsesTheGeneratedPageCapacity()
            throws IOException {
        assertEquals(
                ShaderAbi.BASE_COLOR_PAGE_COUNT,
                descriptorArrayLength(
                        "world.rchit.spv",
                        0,
                        ShaderAbi.DESCRIPTOR_BASE_COLOR_PAGES));
        assertEquals(
                ShaderAbi.BASE_COLOR_PAGE_COUNT,
                descriptorArrayLength(
                        "shadow.rahit.spv",
                        0,
                        ShaderAbi.DESCRIPTOR_BASE_COLOR_PAGES));
    }

    static void optimizedModulesPreservePayloadAbi() throws IOException {
        String tracePayload = "struct(vec3(f32),f32,vec3(f32),"
                + "u32,u32,u32,f32,f32,vec3(f32),f32,u32,u32,u32,u32,"
                + "vec3(f32),u32,vec3(f32),u32)";
        String shadowPayload = "struct(vec4(f32),vec4(f32),vec4(f32),vec4(f32),"
                + "vec2(u32),u32,vec2(u32),vec2(u32))";
        for (String shader : List.of("world.rmiss.spv", "world.rchit.spv")) {
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(shader, STORAGE_INCOMING_RAY_PAYLOAD));
        }
        for (String shader : List.of(
                "shadow.rmiss.spv", "shadow.rchit.spv", "shadow.rahit.spv")) {
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(shader, STORAGE_INCOMING_RAY_PAYLOAD));
        }
        for (String suffix : List.of("", "_ser")) {
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(
                            wavefrontShader("realtime", "camera_trace", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(
                            wavefrontShader("realtime", "delta_walk", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(
                            wavefrontShader("realtime", "guide_delta_walk", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "landing_light_select", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader("realtime", "landing_direct", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "landing_scatter", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(
                            wavefrontShader("realtime", "fixed_bridge_trace", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "fixed_light_select", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader("realtime", "fixed_direct", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "fixed_scatter", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader("realtime", "visible_direct", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(
                            wavefrontShader("offline", "camera_trace", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(
                            wavefrontShader("offline", "bridge_trace", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("offline", "light_select", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader("offline", "direct", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("offline", "scatter", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader(
                                    "realtime", "branch_resolve", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "surface_split", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader(
                                    "realtime", "noisy_output_resolve", suffix),
                            STORAGE_RAY_PAYLOAD));
        }
    }

    static void serReorderedPublishersDoNotReuseProducerSubgroups() throws IOException {
        Set<Integer> cameraTrace = parse(
                wavefrontShader("realtime", "camera_trace", "_ser")).opcodes;
        assertFalse(cameraTrace.contains(OP_GROUP_NON_UNIFORM_ELECT));
        assertFalse(cameraTrace.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST));
        assertFalse(cameraTrace.contains(OP_GROUP_NON_UNIFORM_BALLOT));
        assertFalse(cameraTrace.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT));

        Set<Integer> primary = parse(
                wavefrontShader("realtime", "surface_split", "_ser")).opcodes;
        assertTrue(primary.contains(OP_GROUP_NON_UNIFORM_ELECT));
        assertTrue(primary.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST));
        assertTrue(primary.contains(OP_GROUP_NON_UNIFORM_BALLOT));
        assertTrue(primary.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT));
    }

    static void fixedStagesCompactOnlyAtLandingAndScatter()
            throws IOException {
        Set<Integer> landing = parse(wavefrontShader(
                "realtime", "landing_scatter", "_ser")).opcodes;
        assertTrue(landing.contains(OP_GROUP_NON_UNIFORM_ELECT));
        assertTrue(landing.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST));
        assertTrue(landing.contains(OP_GROUP_NON_UNIFORM_BALLOT));
        assertTrue(landing.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT));

        for (String suffix : List.of("", "_ser")) {
            for (String stage : List.of(
                    "landing_light_select",
                    "landing_direct",
                    "fixed_bridge_trace",
                    "fixed_light_select",
                    "fixed_direct")) {
                Set<Integer> opcodes = parse(
                        wavefrontShader("realtime", stage, suffix)).opcodes;
                assertFalse(opcodes.contains(OP_GROUP_NON_UNIFORM_ELECT),
                        stage + suffix);
                assertFalse(opcodes.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST),
                        stage + suffix);
                assertFalse(opcodes.contains(OP_GROUP_NON_UNIFORM_BALLOT),
                        stage + suffix);
                assertFalse(opcodes.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT),
                        stage + suffix);
            }
        }
    }

    static void offlineStagesCompactOnlyAtScatter() throws IOException {
        Set<Integer> scatter = parse(
                wavefrontShader("offline", "scatter", "_ser")).opcodes;
        assertTrue(scatter.contains(OP_GROUP_NON_UNIFORM_ELECT));
        assertTrue(scatter.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST));
        assertTrue(scatter.contains(OP_GROUP_NON_UNIFORM_BALLOT));
        assertTrue(scatter.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT));

        for (String suffix : List.of("", "_ser")) {
            for (String stage : List.of(
                    "camera_trace", "bridge_trace", "light_select", "direct")) {
                Set<Integer> opcodes = parse(
                        wavefrontShader("offline", stage, suffix)).opcodes;
                assertFalse(opcodes.contains(OP_GROUP_NON_UNIFORM_ELECT), stage + suffix);
                assertFalse(opcodes.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST), stage + suffix);
                assertFalse(opcodes.contains(OP_GROUP_NON_UNIFORM_BALLOT), stage + suffix);
                assertFalse(opcodes.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT), stage + suffix);
            }
        }
    }

    @Test
    void wavefrontBackingHasDeclaredFourKSize() {
        assertEquals(5_374_771_312L,
                RealtimeRayTracingPipeline.wavefrontBytes(3840, 2160));
        assertEquals(2_189_721_632L,
                OfflineRayTracingPipeline.wavefrontBytes(3840, 2160));
        assertEquals(995_328_032L,
                OfflineRayTracingPipeline.queueBytes(3840, 2160));
        assertEquals(2_123_366_400L,
                OfflineRayTracingPipeline.queueCommandOffset(3840, 2160));
        assertEquals(
                2088.2812805175781,
                OfflineRayTracingPipeline.wavefrontBytes(3840, 2160)
                        / (1024.0 * 1024.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> OfflineRayTracingPipeline.wavefrontBytes(0, 2160));
        assertThrows(
                ArithmeticException.class,
                () -> RealtimeRayTracingPipeline.wavefrontBytes(
                        Integer.MAX_VALUE, Integer.MAX_VALUE));
        RealtimeRayTracingPipeline.validateRanges(3840, 2160, 0xffff_ffffL);
        OfflineRayTracingPipeline.validateRanges(3840, 2160, 0xffff_ffffL);
        assertThrows(
                IllegalStateException.class,
                () -> RealtimeRayTracingPipeline.validateDispatch(
                        3840, 2160, 3840 * 2160));
        RealtimeRayTracingPipeline.validateDispatch(3840, 2160, 2 * 3840 * 2160);
        OfflineRayTracingPipeline.validateDispatch(3840, 2160, 1 << 24);
    }

    @Test
    void deferredCompilationClampsDriverConcurrencyToTheHost() {
        assertEquals(1, TraceProgram.deferredWorkerCount(0, 32));
        assertEquals(2, TraceProgram.deferredWorkerCount(2, 32));
        assertEquals(8, TraceProgram.deferredWorkerCount(32, 8));
        assertEquals(32, TraceProgram.deferredWorkerCount(-1, 32));
        assertEquals(1, TraceProgram.deferredWorkerCount(8, 0));
    }

    static void compiledPathRecordsUseIndependentStrides() throws IOException {
        for (String suffix : List.of("", "_ser")) {
            assertRecordStride(
                    wavefrontShader("realtime", "delta_walk", suffix),
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader("realtime", "guide_delta_walk", suffix),
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader("realtime", "landing_light_select", suffix),
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader("realtime", "landing_direct", suffix),
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader("realtime", "landing_scatter", suffix),
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader("offline", "direct", suffix),
                    ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.OFFLINE_WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader("offline", "scatter", suffix),
                    ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.OFFLINE_WAVEFRONT_PATH_RECORD_SIZE);
        }
    }

    private static List<String> wavefrontShaders(
            String renderer, String suffix, List<String> stages) {
        return stages.stream()
                .map(stage -> wavefrontShader(renderer, stage, suffix))
                .toList();
    }

    private static String wavefrontShader(
            String renderer, String stage, String suffix) {
        if ("_ser".equals(suffix)
                && (("realtime".equals(renderer)
                                && "noisy_output_resolve".equals(stage))
                        || ("offline".equals(renderer)
                                && "sample_resolve".equals(stage))
                        || ("offline".equals(renderer)
                                && "light_select".equals(stage))
                        || ("realtime".equals(renderer) && "visible_direct".equals(stage)))) {
            suffix = "";
        }
        return renderer + "_wavefront_" + stage + suffix + ".rgen.spv";
    }

    private static void assertRecordStride(
            String shader, int binding, int expectedStride) throws IOException {
        Spirv module = parse(shader);
        Variable paths = module.variables.stream()
                .filter(variable -> variable.storageClass == STORAGE_BUFFER)
                .filter(variable -> module.bindings.getOrDefault(variable.identifier, -1)
                        == binding)
                .filter(variable -> module.sets.getOrDefault(variable.identifier, -1) == 1)
                .findFirst()
                .orElseThrow();
        Type pointer = module.requireType(paths.type);
        Type block = module.requireType(pointer.operands[1]);
        int arrayIdentifier = block.operands[0];
        assertEquals(expectedStride, module.arrayStrides.get(arrayIdentifier));
    }

    private static Set<Integer> descriptorBindings(
            List<String> shaders, int descriptorSet) throws IOException {
        Set<Integer> result = new HashSet<>();
        for (String shader : shaders) {
            Spirv module = parse(shader);
            for (Variable variable : module.variables) {
                if (module.sets.getOrDefault(variable.identifier, -1) == descriptorSet) {
                    Integer binding = module.bindings.get(variable.identifier);
                    if (binding != null) {
                        result.add(binding);
                    }
                }
            }
        }
        return result;
    }

    private static Set<String> payloadShapes(String shader, int storageClass)
            throws IOException {
        Spirv module = parse(shader);
        Set<String> result = new HashSet<>();
        for (Variable variable : module.variables) {
            if (variable.storageClass != storageClass) {
                continue;
            }
            Type pointer = module.requireType(variable.type);
            result.add(typeShape(module, pointer.operands[1]));
        }
        return result;
    }

    private static int descriptorArrayLength(
            String shader, int descriptorSet, int binding) throws IOException {
        Spirv module = parse(shader);
        Variable descriptor = module.variables.stream()
                .filter(variable -> module.sets.getOrDefault(variable.identifier, -1)
                        == descriptorSet)
                .filter(variable -> module.bindings.getOrDefault(variable.identifier, -1)
                        == binding)
                .findFirst()
                .orElseThrow();
        Type pointer = module.requireType(descriptor.type);
        Type array = module.requireType(pointer.operands[1]);
        if (array.opcode != OP_TYPE_ARRAY) {
            throw new IllegalArgumentException("Descriptor is not a fixed-size array");
        }
        Integer length = module.constants.get(array.operands[1]);
        if (length == null) {
            throw new IllegalArgumentException("Descriptor array has no constant length");
        }
        return length;
    }

    private static Spirv parse(String shader) throws IOException {
        int[] words = spirvWords(shader);
        Spirv result = new Spirv();
        for (int offset = 5; offset < words.length; ) {
            int instruction = words[offset];
            int wordCount = instruction >>> 16;
            int opcode = instruction & 0xffff;
            if (wordCount <= 0 || offset + wordCount > words.length) {
                throw new IllegalArgumentException("Malformed SPIR-V instruction");
            }
            result.opcodes.add(opcode);
            result.opcodeCounts.merge(opcode, 1, Integer::sum);
            if (opcode == OP_TYPE_INT
                    || opcode == OP_TYPE_FLOAT
                    || opcode == OP_TYPE_VECTOR
                    || opcode == OP_TYPE_ARRAY
                    || opcode == OP_TYPE_RUNTIME_ARRAY
                    || opcode == OP_TYPE_STRUCT
                    || opcode == OP_TYPE_POINTER) {
                result.types.put(
                        words[offset + 1],
                        new Type(opcode, Arrays.copyOfRange(
                                words, offset + 2, offset + wordCount)));
            } else if (opcode == OP_CONSTANT && wordCount == 4) {
                result.constants.put(words[offset + 2], words[offset + 3]);
            } else if (opcode == OP_VARIABLE) {
                result.variables.add(new Variable(
                        words[offset + 1], words[offset + 2], words[offset + 3]));
            } else if (opcode == OP_DECORATE && wordCount >= 4) {
                int target = words[offset + 1];
                switch (words[offset + 2]) {
                    case DECORATION_ARRAY_STRIDE ->
                            result.arrayStrides.put(target, words[offset + 3]);
                    case DECORATION_BINDING ->
                            result.bindings.put(target, words[offset + 3]);
                    case DECORATION_DESCRIPTOR_SET ->
                            result.sets.put(target, words[offset + 3]);
                    default -> { }
                }
            }
            offset += wordCount;
        }
        return result;
    }

    private static int[] spirvWords(String shader) throws IOException {
        String resource = "/prime/shaders/" + shader;
        byte[] bytes;
        try (InputStream input = TracePipelinesContractTest.class
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing compiled shader " + resource);
            }
            bytes = input.readAllBytes();
        }
        int[] words = new int[bytes.length / Integer.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(words);
        if (words.length < 5 || words[0] != 0x0723_0203) {
            throw new IllegalArgumentException("Malformed SPIR-V header");
        }
        return words;
    }

    private static String typeShape(Spirv module, int identifier) {
        Type type = module.requireType(identifier);
        return switch (type.opcode) {
            case OP_TYPE_INT -> (type.operands[1] == 0 ? "u" : "i") + type.operands[0];
            case OP_TYPE_FLOAT -> "f" + type.operands[0];
            case OP_TYPE_VECTOR -> "vec" + type.operands[1]
                    + "(" + typeShape(module, type.operands[0]) + ")";
            case OP_TYPE_STRUCT -> "struct("
                    + Arrays.stream(type.operands)
                            .mapToObj(member -> typeShape(module, member))
                            .reduce((left, right) -> left + "," + right)
                            .orElse("")
                    + ")";
            default -> throw new IllegalArgumentException(
                    "Unsupported SPIR-V type " + type.opcode);
        };
    }

    private static final class Spirv {
        final Map<Integer, Type> types = new HashMap<>();
        final Map<Integer, Integer> bindings = new HashMap<>();
        final Map<Integer, Integer> sets = new HashMap<>();
        final Map<Integer, Integer> constants = new HashMap<>();
        final Map<Integer, Integer> arrayStrides = new HashMap<>();
        final List<Variable> variables = new ArrayList<>();
        final Set<Integer> opcodes = new HashSet<>();
        final Map<Integer, Integer> opcodeCounts = new HashMap<>();

        int opcodeCount(int opcode) {
            return this.opcodeCounts.getOrDefault(opcode, 0);
        }

        Type requireType(int identifier) {
            Type type = this.types.get(identifier);
            if (type == null) {
                throw new IllegalArgumentException("Missing SPIR-V type " + identifier);
            }
            return type;
        }
    }

    private record Type(int opcode, int[] operands) { }
    private record Variable(int type, int identifier, int storageClass) { }
}
