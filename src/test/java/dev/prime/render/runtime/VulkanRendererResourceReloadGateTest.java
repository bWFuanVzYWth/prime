package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class VulkanRendererResourceReloadGateTest {
    @Test
    void generationRemainsWithheldUntilItsExactTransactionCompletes() {
        VulkanRenderer.ReloadGate<Object> gate = new VulkanRenderer.ReloadGate<>();
        Object reload = new Object();

        gate.begin(reload);

        assertTrue(gate.active());
        assertThrows(IllegalStateException.class, gate::requireIdle);
        assertThrows(IllegalStateException.class, () -> gate.begin(new Object()));
        assertThrows(IllegalArgumentException.class, () -> gate.complete(new Object()));
        assertTrue(gate.active());
        gate.complete(reload);
        assertFalse(gate.active());
        gate.requireIdle();
    }

    @Test
    void staleOrDuplicateCompletionCannotReleaseAnotherGeneration() {
        VulkanRenderer.ReloadGate<Object> gate = new VulkanRenderer.ReloadGate<>();
        Object first = new Object();
        Object second = new Object();
        gate.begin(first);
        gate.complete(first);
        gate.begin(second);

        assertThrows(IllegalArgumentException.class, () -> gate.complete(first));
        assertTrue(gate.active());
        gate.complete(second);
        assertFalse(gate.active());
    }
}
