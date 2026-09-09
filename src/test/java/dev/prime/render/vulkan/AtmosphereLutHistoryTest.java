// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class AtmosphereLutHistoryTest {
    private static final int ALL =
            AtmosphereLutHistory.STATIC
                    | AtmosphereLutHistory.SKY
                    | AtmosphereLutHistory.AERIAL;

    @Test
    void abandonedCandidateDoesNotAdvanceCommittedKeys() {
        AtmosphereLutHistory history = new AtmosphereLutHistory(4);
        assertFalse(history.staticPrepared());
        Arrays.fill(history.beginCandidate(), 7);

        assertEquals(ALL, history.prepareCandidate(11, 13));
        history.abandon();

        Arrays.fill(history.beginCandidate(), 7);
        assertEquals(ALL, history.prepareCandidate(11, 13));
        history.commit();
        assertTrue(history.staticPrepared());

        Arrays.fill(history.beginCandidate(), 7);
        assertEquals(0, history.prepareCandidate(11, 13));
    }

    @Test
    void committedKeysSelectOnlyTheChangedLuts() {
        AtmosphereLutHistory history = new AtmosphereLutHistory(4);
        Arrays.fill(history.beginCandidate(), 1);
        history.prepareCandidate(2, 3);
        history.commit();

        Arrays.fill(history.beginCandidate(), 1);
        assertEquals(
                AtmosphereLutHistory.SKY,
                history.prepareCandidate(5, 3));
        history.commit();

        Arrays.fill(history.beginCandidate(), 9);
        assertEquals(
                AtmosphereLutHistory.AERIAL,
                history.prepareCandidate(5, 3));
    }

    @Test
    void bootstrapStaticSubmissionLeavesDynamicTablesInvalid() {
        AtmosphereLutHistory history = new AtmosphereLutHistory(4);

        history.staticSubmitted();
        Arrays.fill(history.beginCandidate(), 1);

        assertEquals(
                AtmosphereLutHistory.SKY | AtmosphereLutHistory.AERIAL,
                history.prepareCandidate(2, 3));
    }

    @Test
    void overlappingCandidatesAreRejected() {
        AtmosphereLutHistory history = new AtmosphereLutHistory(1);
        history.beginCandidate()[0] = 1;
        history.prepareCandidate(2, 3);

        assertThrows(IllegalStateException.class, history::beginCandidate);
        assertThrows(
                IllegalStateException.class,
                () -> history.prepareCandidate(2, 3));
    }
}
