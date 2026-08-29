package dev.prime.render.vulkan.terrain;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class TerrainUpdateTransactionTest {
    @Test
    void unsubmittedEmptyUpdateMayPublishAndClosesIdempotently() {
        TerrainUpdateTransaction transaction = emptyTransaction();

        transaction.published();
        RuntimeException retirementFailure = new IllegalStateException("retirement");

        assertSame(retirementFailure, transaction.abort(retirementFailure));
        transaction.close();
        transaction.close();
        assertThrows(IllegalStateException.class, transaction::published);
        assertThrows(IllegalStateException.class, transaction::replacements);
    }

    @Test
    void submittedUpdateCanPublishOnlyOnce() {
        TerrainUpdateTransaction transaction = emptyTransaction();

        transaction.submitted();

        assertThrows(IllegalStateException.class, transaction::submitted);
        assertThrows(IllegalStateException.class, transaction::replacements);
        transaction.published();
        assertThrows(IllegalStateException.class, transaction::published);
        transaction.close();
    }

    @Test
    void abortBeforeSubmissionClosesOwnershipAndPreservesTheOriginalFailure() {
        TerrainUpdateTransaction transaction = emptyTransaction();
        RuntimeException original = new IllegalArgumentException("recording");

        assertSame(original, transaction.abort(original));
        assertSame(original, transaction.abort(original));
        assertThrows(IllegalStateException.class, transaction::submitted);
        assertThrows(IllegalStateException.class, transaction::published);
    }

    private static TerrainUpdateTransaction emptyTransaction() {
        return new TerrainUpdateTransaction(null, null, null, null, null, 0);
    }
}
