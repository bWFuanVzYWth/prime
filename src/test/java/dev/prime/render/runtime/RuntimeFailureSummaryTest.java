// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RuntimeFailureSummaryTest {
    @Test
    void wrappedFailureKeepsRootCauseAndTerrainContext() {
        IllegalArgumentException root = new IllegalArgumentException(
                "Captured Section quad contains a non-normalized\n local texture UV");
        IllegalStateException failure = new IllegalStateException(
                "Terrain section (900, -4, -732) failed in cluster (900, -4, -732)", root);

        assertEquals(
                "IllegalArgumentException: Captured Section quad contains a non-normalized "
                        + "local texture UV | Terrain section (900, -4, -732) failed in cluster "
                        + "(900, -4, -732)",
                RuntimeFailureSummary.describe(failure));
    }

    @Test
    void titleCarriesInstalledVersionAndState() {
        assertEquals(
                "Prime FAILED | 1.2.0",
                RuntimeFailureSummary.title("1.2.0", RuntimeState.FAILED));
    }

    @Test
    void directFailureKeepsTypeAndMessage() {
        assertEquals(
                "IllegalStateException: host rejected frame",
                RuntimeFailureSummary.describe(
                        new IllegalStateException("host rejected frame")));
    }

    @Test
    void hostileTextIsSingleLineAndBoundedBeforePixelFitting() {
        String longMessage = "bad\r\n\tvalue ".repeat(100);
        String summary = RuntimeFailureSummary.describe(
                new IllegalArgumentException(longMessage), "terrain\u202esetup");

        assertTrue(summary.startsWith("IllegalArgumentException: "));
        assertTrue(summary.endsWith(" | terrainsetup"));
        assertTrue(summary.codePointCount(0, summary.length()) <= 260);
        assertFalse(summary.contains("\r"));
        assertFalse(summary.contains("\n"));
        assertFalse(summary.contains("\t"));
        assertFalse(summary.contains("\u202e"));
    }

    @Test
    void missingMessageStillNamesTheRootException() {
        assertEquals(
                "IllegalArgumentException",
                RuntimeFailureSummary.describe(
                        new IllegalStateException(new IllegalArgumentException())));
    }
}
