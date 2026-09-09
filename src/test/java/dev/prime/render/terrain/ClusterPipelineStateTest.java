// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ClusterPipelineStateTest {
    @Test
    void oneGenerationCannotBeQueuedWhileItIsInFlightOrReady() {
        ClusterPipelineState state = new ClusterPipelineState();
        long key = 17L;
        long generation = 3L;

        assertTrue(state.enqueue(key, generation));
        ClusterPipelineState.Cancellation cancellation = state.beginInFlight(key, generation);
        assertFalse(state.enqueue(key, generation));
        assertFalse(cancellation.cancelled());
        assertTrue(state.completeToReady(key, generation));
        assertFalse(state.enqueue(key, generation));
        assertFalse(state.completeToReady(key, generation));

        state.consumeReady(key, generation);
        assertTrue(state.enqueue(key, generation));
    }

    @Test
    void newerGenerationMayQueueBehindOlderWork() {
        ClusterPipelineState state = new ClusterPipelineState();
        long key = 23L;

        assertTrue(state.enqueue(key, 4L));
        ClusterPipelineState.Cancellation cancellation = state.beginInFlight(key, 4L);
        assertTrue(state.enqueue(key, 5L));
        assertTrue(cancellation.cancelled());
        state.cancelInFlight(key, 4L);

        assertTrue(state.isQueued(key, 5L));
        assertFalse(state.isQueued(key, 4L));
    }

    @Test
    void cancelledWorkerReleasesInFlightAndRequeuesTheSameGeneration() {
        ClusterPipelineState state = new ClusterPipelineState();
        long key = 29L;
        long generation = 7L;

        assertTrue(state.enqueue(key, generation));
        state.beginInFlight(key, generation);
        state.cancelInFlight(key, generation);

        assertFalse(state.hasInFlight(key));
        assertTrue(state.enqueue(key, generation));
        assertTrue(state.isQueued(key, generation));
    }

    @Test
    void clearingThePipelineCancelsEveryWorkerBuild() {
        ClusterPipelineState state = new ClusterPipelineState();
        state.enqueue(31L, 1L);
        ClusterPipelineState.Cancellation first = state.beginInFlight(31L, 1L);
        state.enqueue(37L, 2L);
        ClusterPipelineState.Cancellation second = state.beginInFlight(37L, 2L);

        state.clear();

        assertTrue(first.cancelled());
        assertTrue(second.cancelled());
        assertFalse(state.hasInFlight(31L));
        assertFalse(state.hasInFlight(37L));
    }
}
