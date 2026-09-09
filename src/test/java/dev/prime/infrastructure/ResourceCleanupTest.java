// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ResourceCleanupTest {
    @Test
    void cleanupPreservesThePrimaryFailureAndAttemptsEveryResource() {
        RuntimeException primary = new IllegalStateException("create");
        int[] attempts = {0};

        RuntimeException result = ResourceCleanup.destroy(() -> {
            attempts[0]++;
            throw new IllegalStateException("destroy");
        }, primary);
        result = ResourceCleanup.close(() -> {
            attempts[0]++;
            throw new Exception("close");
        }, result);
        result = ResourceCleanup.run(() -> {
            attempts[0]++;
            throw new IllegalArgumentException("run");
        }, result);

        assertSame(primary, result);
        assertEquals(3, attempts[0]);
        assertEquals(3, result.getSuppressed().length);
        RuntimeException finalResult = result;
        assertSame(result, assertThrows(
                RuntimeException.class,
                () -> ResourceCleanup.throwIfFailed(finalResult)));
    }
}
