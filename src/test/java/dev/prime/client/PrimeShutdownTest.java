package dev.prime.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PrimeShutdownTest {
    @Test
    void ngxRendererRetiresBeforeStreamlineBaseRuntime() {
        List<String> order = new ArrayList<>();

        RuntimeException failure = PrimeShutdown.run(
                () -> order.add("frame-generation"),
                () -> order.add("reflex"),
                () -> order.add("rr-renderer"),
                () -> order.add("streamline"));

        assertEquals(
                List.of("frame-generation", "reflex", "rr-renderer", "streamline"),
                order);
        assertNull(failure);
    }

    @Test
    void earlierFailureDoesNotSkipLaterNativeOwners() {
        List<String> order = new ArrayList<>();

        RuntimeException failure = PrimeShutdown.run(
                () -> {
                    order.add("frame-generation");
                    throw new IllegalStateException("fg");
                },
                () -> order.add("reflex"),
                () -> order.add("rr-renderer"),
                () -> order.add("streamline"));

        assertNotNull(failure);
        assertEquals("fg", failure.getMessage());
        assertEquals(
                List.of("frame-generation", "reflex", "rr-renderer", "streamline"),
                order);
    }
}
