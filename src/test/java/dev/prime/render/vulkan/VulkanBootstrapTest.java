// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class VulkanBootstrapTest {
    @Test
    void newNegotiationClearsThePreviousDevice() {
        VulkanBootstrap.StateMachine<Object> states = new VulkanBootstrap.StateMachine<>(capabilities("initial"));
        VulkanBootstrap.Negotiation first = states.begin();
        states.record(first, capabilities("first"));
        Object device = new Object();
        states.attach(first, device);
        assertSame(device, states.snapshot().device());

        VulkanBootstrap.Negotiation second = states.begin();

        assertEquals(first.generation() + 1L, second.generation());
        assertNull(states.snapshot().device());
    }

    @Test
    void rejectsStaleAndDuplicateTransitions() {
        VulkanBootstrap.StateMachine<Object> states = new VulkanBootstrap.StateMachine<>(capabilities("initial"));
        VulkanBootstrap.Negotiation stale = states.begin();
        VulkanBootstrap.Negotiation current = states.begin();

        assertThrows(IllegalStateException.class, () -> states.record(stale, capabilities("stale")));
        states.record(current, capabilities("current"));
        assertThrows(IllegalStateException.class, () -> states.record(current, capabilities("duplicate")));
        states.attach(current, new Object());
        assertThrows(IllegalStateException.class, () -> states.attach(current, new Object()));
    }

    @Test
    void deviceCannotAttachBeforeCapabilities() {
        VulkanBootstrap.StateMachine<Object> states = new VulkanBootstrap.StateMachine<>(capabilities("initial"));
        VulkanBootstrap.Negotiation negotiation = states.begin();

        assertThrows(IllegalStateException.class, () -> states.attach(negotiation, new Object()));
    }

    private static VulkanCapabilities capabilities(String name) {
        return VulkanCapabilities.unavailable(name, "test");
    }
}
