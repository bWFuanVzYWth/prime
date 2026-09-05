package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.terrain.CpuSectionMesh;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.junit.jupiter.api.Test;

final class PreparedBlasTest {
    private static final long POSITION_ADDRESS = 0x1_0000_0000L;

    @Test
    void oneBlasAcceptsLargeClusterCountsUntilARealAbiBoundary() {
        long seventyGibTriangles = (70L << 30)
                / (9L * Float.BYTES
                        + (long) CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES);
        assertEquals(
                seventyGibTriangles,
                PreparedBlas.validateCounts(seventyGibTriangles, 0L, 0L, -1L));
        assertThrows(
                IllegalStateException.class,
                () -> PreparedBlas.validateCounts(
                        seventyGibTriangles, 0L, 0L, seventyGibTriangles - 1L));
        assertThrows(
                IllegalStateException.class,
                () -> PreparedBlas.validateCounts(
                        0x1_0000_0000L / 3L + 1L, 0L, 0L, -1L));
        assertThrows(
                IllegalStateException.class,
                () -> PreparedBlas.validateCounts(
                        0x2aaa_aaabL, 0x2aaa_aaabL, 0x2aaa_aaabL, -1L));
    }

    @Test
    void cutoutGeometryStartsAfterOpaqueVertices() {
        assertEquals(
                POSITION_ADDRESS + 12L * 3L * 3L * Float.BYTES,
                PreparedBlas.cutoutGeometryVertexAddress(POSITION_ADDRESS, 12, 5));
    }

    @Test
    void emptyCutoutGeometryKeepsAnAddressInsideThePositionBuffer() {
        assertEquals(
                POSITION_ADDRESS,
                PreparedBlas.cutoutGeometryVertexAddress(POSITION_ADDRESS, 12, 0));
    }

    @Test
    void cutoutOnlyGeometryStartsAtTheBeginningOfThePositionBuffer() {
        assertEquals(
                POSITION_ADDRESS,
                PreparedBlas.cutoutGeometryVertexAddress(POSITION_ADDRESS, 0, 5));
    }

    @Test
    void rejectsNegativeTriangleCounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PreparedBlas.cutoutGeometryVertexAddress(POSITION_ADDRESS, -1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> PreparedBlas.cutoutGeometryVertexAddress(POSITION_ADDRESS, 0, -1));
    }

    @Test
    void transmissiveGeometryStartsAfterOpaqueAndCutoutPartitions() {
        long expected = POSITION_ADDRESS + (12L + 5L) * 9L * Float.BYTES;
        assertEquals(
                expected,
                PreparedBlas.transmissiveGeometryVertexAddress(
                        POSITION_ADDRESS, 12, 5, 7));
    }

    @Test
    void emptyTransmissiveGeometryUsesLiveBufferBase() {
        assertEquals(
                POSITION_ADDRESS,
                PreparedBlas.transmissiveGeometryVertexAddress(
                        POSITION_ADDRESS, 12, 5, 0));
    }

    @Test
    void compactionPolicyControlsOnlyTheCompactionBuildFlag() {
        int enabled = PreparedBlas.buildFlags(PreparedBlas.CompactionPolicy.ENABLED);
        int disabled = PreparedBlas.buildFlags(PreparedBlas.CompactionPolicy.DISABLED);
        assertTrue((disabled & org.lwjgl.vulkan.KHRRayTracingPositionFetch
                .VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_DATA_ACCESS_BIT_KHR) != 0);

        assertTrue((enabled
                        & KHRAccelerationStructure
                                .VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)
                != 0);
        assertFalse((disabled
                        & KHRAccelerationStructure
                                .VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)
                != 0);
        assertTrue((enabled
                        & KHRAccelerationStructure
                                .VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                != 0);
        assertEquals(
                disabled,
                enabled
                        & ~KHRAccelerationStructure
                                .VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR);
    }

    @Test
    void compactionLifecycleCoversSuccessfulPublication() {
        PreparedBlas.CompactionState state = PreparedBlas.CompactionState.BUILD_PENDING;
        state = transition(state, PreparedBlas.CompactionEvent.BUILD_SUBMITTED);
        state = transition(state, PreparedBlas.CompactionEvent.QUERY_COMPLETED);
        state = transition(state, PreparedBlas.CompactionEvent.BENEFICIAL_SIZE_RESOLVED);
        state = transition(state, PreparedBlas.CompactionEvent.TARGET_PREPARED);
        state = transition(state, PreparedBlas.CompactionEvent.TARGET_PUBLISHED);

        assertEquals(PreparedBlas.CompactionState.RETIRING_SOURCE, state);
        assertEquals(
                PreparedBlas.CompactionState.COMPACTED,
                transition(state, PreparedBlas.CompactionEvent.SOURCE_RETIRED));
    }

    @Test
    void compactionLifecycleSupportsBothTargetRollbackPaths() {
        assertEquals(
                PreparedBlas.CompactionState.READY,
                transition(
                        PreparedBlas.CompactionState.PREPARED,
                        PreparedBlas.CompactionEvent.PREPARED_TARGET_ROLLED_BACK));
        PreparedBlas.CompactionState abandoning = transition(
                PreparedBlas.CompactionState.PREPARED,
                PreparedBlas.CompactionEvent.TARGET_ABANDONED);
        assertEquals(PreparedBlas.CompactionState.ABANDONING_TARGET, abandoning);
        assertEquals(
                PreparedBlas.CompactionState.READY,
                transition(
                        abandoning,
                        PreparedBlas.CompactionEvent.ABANDONED_TARGET_RETIRED));
    }

    @Test
    void unbeneficialAndOutOfOrderCompactionOperationsAreTerminalOrRejected() {
        PreparedBlas.CompactionState terminal = transition(
                PreparedBlas.CompactionState.QUERY_READY,
                PreparedBlas.CompactionEvent.UNBENEFICIAL_SIZE_RESOLVED);

        assertEquals(PreparedBlas.CompactionState.NOT_BENEFICIAL, terminal);
        assertThrows(
                IllegalStateException.class,
                () -> transition(
                        terminal,
                        PreparedBlas.CompactionEvent.TARGET_PREPARED));
        assertThrows(
                IllegalStateException.class,
                () -> transition(
                        PreparedBlas.CompactionState.BUILD_PENDING,
                        PreparedBlas.CompactionEvent.TARGET_PREPARED));
    }

    @Test
    void lateTimelineCallbacksAreHarmlessAfterDestruction() {
        assertEquals(
                PreparedBlas.CompactionState.DESTROYED,
                transition(
                        PreparedBlas.CompactionState.DESTROYED,
                        PreparedBlas.CompactionEvent.QUERY_COMPLETED));
        assertEquals(
                PreparedBlas.CompactionState.DESTROYED,
                transition(
                        PreparedBlas.CompactionState.DESTROYED,
                        PreparedBlas.CompactionEvent.ABANDONED_TARGET_RETIRED));
        assertEquals(
                PreparedBlas.CompactionState.DESTROYED,
                transition(
                        PreparedBlas.CompactionState.DESTROYED,
                        PreparedBlas.CompactionEvent.SOURCE_RETIRED));
    }

    private static PreparedBlas.CompactionState transition(
            PreparedBlas.CompactionState state,
            PreparedBlas.CompactionEvent event) {
        return PreparedBlas.transition(state, event);
    }
}
