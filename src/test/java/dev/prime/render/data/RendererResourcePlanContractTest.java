package dev.prime.render.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.shader.ShaderAbi;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RendererResourcePlanContractTest {
    @Test
    void currentStaticMemoryLedgerMatchesExecutableAbi() {
        RendererDataContracts.MemoryPlan realtime =
                RendererDataContracts.memoryPlan("realtime-wavefront-current");
        RendererDataContracts.MemoryPlan offline =
                RendererDataContracts.memoryPlan("offline-wavefront-current");

        assertEquals(
                ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE
                                * ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL
                        + ShaderAbi.WAVEFRONT_AREA_RECORD_SIZE
                        + ShaderAbi.WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL * Integer.BYTES,
                realtime.renderBytesPerPixel());
        assertEquals(568, realtime.renderBytesPerPixel());
        assertEquals(
                ShaderAbi.WAVEFRONT_AREA_GUIDE_RECORD_SIZE
                        + ShaderAbi.WAVEFRONT_SURFACE_RECORD_SIZE
                                * ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL
                        + Math.max(
                                ShaderAbi.WAVEFRONT_DETACHED_GUIDE_RECORD_SIZE,
                                ShaderAbi.WAVEFRONT_STAGED_LIGHT_RECORD_SIZE
                                        * ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL),
                ShaderAbi.WAVEFRONT_AREA_RECORD_SIZE,
                "detached guide must alias the later staged-light region");
        assertEquals(
                ShaderAbi.OFFLINE_WAVEFRONT_PATH_RECORD_SIZE
                        + ShaderAbi.OFFLINE_WAVEFRONT_STAGE_RECORD_SIZE
                        + ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL
                                * Integer.BYTES,
                offline.renderBytesPerPixel());
        assertEquals(244, offline.renderBytesPerPixel());
        assertEquals(95, RendererDataContracts
                .memoryPlan("raw-wavefront-images-current").renderBytesPerPixel());
        assertEquals(137, RendererDataContracts
                .memoryPlan("dlss-rr-images-current").renderBytesPerPixel());
        assertEquals(8, RendererDataContracts
                .memoryPlan("dlss-rr-images-current").displayBytesPerPixel());
        assertEquals(291, RendererDataContracts
                .memoryPlan("nrd-prime-images-current").renderBytesPerPixel());
        assertEquals(
                ShaderAbi.OFFLINE_WAVEFRONT_SURFACE_RECORD_SIZE
                        + ShaderAbi.OFFLINE_WAVEFRONT_STAGED_LIGHT_RECORD_SIZE,
                ShaderAbi.OFFLINE_WAVEFRONT_STAGE_RECORD_SIZE,
                "offline light selection must end before queue commands begin");
    }

    @Test
    void memoryLedgerUsesUniqueSemanticDebugLabelsAndCheckedExtentArithmetic() {
        HashSet<String> labels = new HashSet<>();
        for (RendererDataContracts.MemoryPlan plan : RendererDataContracts.MEMORY_PLANS) {
            for (RendererDataContracts.MemoryItem item : plan.items()) {
                assertFalse(item.debugLabel().isBlank());
                assertTrue(labels.add(item.debugLabel()), "duplicate label " + item.debugLabel());
            }
        }
        RendererDataContracts.MemoryPlan rr =
                RendererDataContracts.memoryPlan("dlss-rr-images-current");
        assertEquals(
                137L * 1920L * 1080L + 8L * 3840L * 2160L,
                rr.bytes(1920, 1080, 3840, 2160));
        assertThrows(
                IllegalArgumentException.class,
                () -> rr.bytes(0, 1080, 3840, 2160));
        assertThrows(
                ArithmeticException.class,
                () -> rr.bytes(Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void aliasesHaveOrderedNonOverlappingLifetimesAndExplicitConversions() {
        List<RendererDataContracts.Binding> aliased = RendererDataContracts.BINDINGS.stream()
                .filter(binding -> binding.lifetime().aliasGroup().equals("motion-or-transport"))
                .toList();
        assertEquals(2, aliased.size());
        RendererDataContracts.Binding transport = aliased.stream()
                .filter(binding -> binding.semantic().equals("TransportScratch"))
                .findFirst()
                .orElseThrow();
        RendererDataContracts.Binding motion = aliased.stream()
                .filter(binding -> binding.semantic().equals("VisibleMotionUv"))
                .findFirst()
                .orElseThrow();
        assertTrue(phase(transport.lifetime().lastRead())
                < phase(motion.lifetime().firstWrite()));
        assertEquals("transport", transport.conversion().owner());
        assertEquals("backend-adapter", motion.conversion().owner());
        assertFalse(motion.verification().behaviorOracle().isBlank());

        assertOrderedAlias(
                "rr-transport-or-input-color",
                "rr-transport-scratch",
                "rr-input-color-overwrite");
        assertOrderedAlias(
                "rr-penumbra-or-hit-distance",
                "rr-sun-penumbra",
                "rr-specular-hit-distance-overwrite");

        RendererDataContracts.Binding visibleHistory = RendererDataContracts.BINDINGS.stream()
                .filter(binding -> binding.semantic().equals("VisibleHistoryPosition"))
                .findFirst()
                .orElseThrow();
        assertEquals("rgba32f-baseline", visibleHistory.encoding());
        assertEquals("none", visibleHistory.lifetime().aliasGroup());

        RendererDataContracts.Binding guideSurface = RendererDataContracts.BINDINGS.stream()
                .filter(binding -> binding.id().equals("realtime-guide-surface-carrier"))
                .findFirst()
                .orElseThrow();
        assertEquals("GuideSurface", guideSurface.semantic());
        assertEquals("rgba32f-baseline", guideSurface.encoding());
        assertEquals(
                "realtime.normalRoughness|realtime.reflectionNormalRoughness",
                guideSurface.descriptorOrOffset());
        assertEquals("transport-reconstruction-boundary", guideSurface.conversion().owner());
        assertEquals(List.of("trace", "reconstruction-prepare", "reconstruction"),
                guideSurface.lifetime().consumers());
        assertEquals("core-reconstruction", visibleHistory.conversion().owner());
    }

    @Test
    void benchmarkPlansAlwaysNameTheirCorrectnessOracle() {
        assertEquals(3, RendererDataContracts.BENCHMARKS.size());
        for (RendererDataContracts.Benchmark benchmark : RendererDataContracts.BENCHMARKS) {
            assertFalse(benchmark.fixture().isBlank());
            assertFalse(benchmark.correctnessOracle().isBlank());
            assertTrue(benchmark.warmupIterations() > 0);
            assertTrue(benchmark.measurementIterations() > 0);
        }
    }

    private static int phase(String name) {
        int index = RendererDataContracts.PHASES.indexOf(name);
        assertTrue(index >= 0, "unknown phase " + name);
        return index;
    }

    private static void assertOrderedAlias(String group, String firstId, String secondId) {
        List<RendererDataContracts.Binding> bindings = RendererDataContracts.BINDINGS.stream()
                .filter(binding -> binding.lifetime().aliasGroup().equals(group))
                .toList();
        assertEquals(2, bindings.size());
        RendererDataContracts.Binding first = bindings.stream()
                .filter(binding -> binding.id().equals(firstId))
                .findFirst()
                .orElseThrow();
        RendererDataContracts.Binding second = bindings.stream()
                .filter(binding -> binding.id().equals(secondId))
                .findFirst()
                .orElseThrow();
        assertTrue(phase(first.lifetime().lastRead())
                < phase(second.lifetime().firstWrite()));
        assertEquals(first.resourceKind(), second.resourceKind());
        assertEquals(first.extentSource(), second.extentSource());
        assertEquals(first.encoding(), second.encoding());
    }
}
