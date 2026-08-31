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
        assertEquals(648, realtime.renderBytesPerPixel());
        assertEquals(
                ShaderAbi.OFFLINE_WAVEFRONT_PATH_RECORD_SIZE
                        + ShaderAbi.OFFLINE_WAVEFRONT_STAGE_RECORD_SIZE
                        + ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL
                                * Integer.BYTES,
                offline.renderBytesPerPixel());
        assertEquals(264, offline.renderBytesPerPixel());
        assertEquals(95, RendererDataContracts
                .memoryPlan("raw-wavefront-images-current").renderBytesPerPixel());
        assertEquals(147, RendererDataContracts
                .memoryPlan("dlss-rr-images-current").renderBytesPerPixel());
        assertEquals(8, RendererDataContracts
                .memoryPlan("dlss-rr-images-current").displayBytesPerPixel());
        assertEquals(291, RendererDataContracts
                .memoryPlan("nrd-prime-images-current").renderBytesPerPixel());
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
                147L * 1920L * 1080L + 8L * 3840L * 2160L,
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
        RendererDataContracts.Binding visibleDelta = aliased.stream()
                .filter(binding -> binding.semantic().equals("VisibleSurfaceMotionDelta"))
                .findFirst()
                .orElseThrow();
        RendererDataContracts.Binding motion = aliased.stream()
                .filter(binding -> binding.semantic().equals("VisibleMotionUv"))
                .findFirst()
                .orElseThrow();
        assertTrue(phase(visibleDelta.lifetime().lastRead())
                < phase(motion.lifetime().firstWrite()));
        assertEquals("trace-interop-boundary", visibleDelta.conversion().owner());
        assertEquals("backend-adapter", motion.conversion().owner());
        assertFalse(motion.verification().behaviorOracle().isBlank());
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
}
