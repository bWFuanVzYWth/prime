package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PendingSubmissionTest {
    @Test
    void tokenCompletesExactlyOnceAndCannotCrossAnOwnerBoundary() {
        PendingSubmission<Object> pending = new PendingSubmission<>();
        Object token = new Object();
        Object foreign = new Object();

        pending.begin(token);

        assertTrue(pending.active());
        assertThrows(
                IllegalArgumentException.class,
                () -> pending.complete(foreign, "foreign"));
        assertTrue(pending.active());
        pending.complete(token, "foreign");
        assertFalse(pending.active());
        assertThrows(
                IllegalArgumentException.class,
                () -> pending.complete(token, "already completed"));
    }

    @Test
    void replacementIsRejectedUntilSubmitAbandonOrCloseClearsTheToken() {
        PendingSubmission<Object> pending = new PendingSubmission<>();
        Object first = new Object();
        Object second = new Object();
        pending.begin(first);

        assertThrows(IllegalStateException.class, () -> pending.begin(second));
        assertSame(first, pending.clear());
        assertNull(pending.clear());

        pending.begin(second);
        assertSame(second, pending.clear());
        assertFalse(pending.active());
    }
}
