// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ClusterGenerationTrackerTest {
    @Test
    void invalidationMakesOlderWorkerResultsStale() {
        ClusterGenerationTracker tracker = new ClusterGenerationTracker();
        long key = 42L;
        assertTrue(tracker.isCurrent(key, 0L));
        long first = tracker.advance(key);
        long second = tracker.advance(key);
        assertEquals(1L, first);
        assertEquals(2L, second);
        assertFalse(tracker.isCurrent(key, first));
        assertTrue(tracker.isCurrent(key, second));
        long oldEpoch = tracker.worldEpoch();
        tracker.resetWorld();
        assertEquals(0L, tracker.current(key));
        assertFalse(tracker.isCurrent(oldEpoch, key, 0L));
        assertTrue(tracker.isCurrent(tracker.worldEpoch(), key, 0L));
    }
}
