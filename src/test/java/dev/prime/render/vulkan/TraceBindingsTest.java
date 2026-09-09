// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TraceBindingsTest {
    @Test
    void publishesOnlyExplicitDescriptorAndReadinessState() {
        TraceBindings bindings = new TraceBindings(7L);
        assertEquals(7L, bindings.descriptorSetLayout());
        assertFalse(bindings.ready());
        assertThrows(IllegalStateException.class, bindings::descriptorSet);

        bindings.publishDescriptorSet(11L);
        bindings.setReady(true);

        assertEquals(11L, bindings.descriptorSet());
        assertTrue(bindings.ready());
        bindings.setReady(false);
        assertFalse(bindings.ready());
    }

    @Test
    void closeRevokesEveryBorrowedBinding() {
        TraceBindings bindings = new TraceBindings(7L);
        bindings.publishDescriptorSet(11L);
        bindings.setReady(true);

        bindings.close();

        assertFalse(bindings.ready());
        assertThrows(IllegalStateException.class, bindings::descriptorSetLayout);
        assertThrows(IllegalStateException.class, bindings::descriptorSet);
        assertThrows(IllegalStateException.class, () -> bindings.setReady(true));
    }
}
