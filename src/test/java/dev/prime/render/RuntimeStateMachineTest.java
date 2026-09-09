// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class RuntimeStateMachineTest {
    @Test
    void followsTheRendererLifecycleAndMakesFailureSticky() {
        RuntimeStateMachine states = new RuntimeStateMachine();
        assertEquals(RuntimeState.UNAVAILABLE, states.current());
        states.rendererReady();
        assertEquals(RuntimeState.WAITING_FOR_WORLD, states.current());
        states.worldStreaming(false);
        assertEquals(RuntimeState.STREAMING, states.current());
        states.worldStreaming(true);
        assertEquals(RuntimeState.ACTIVE, states.current());
        states.worldStreaming(false);
        assertEquals(RuntimeState.ACTIVE, states.current());
        states.worldAbsent();
        assertEquals(RuntimeState.WAITING_FOR_WORLD, states.current());
        states.fail();
        states.rendererReady();
        states.worldStreaming(true);
        assertEquals(RuntimeState.FAILED, states.current());
        states.shutdown();
        assertEquals(RuntimeState.UNAVAILABLE, states.current());
    }

    @Test
    void activeOwnershipIsStickyUntilTheWorldChanges() {
        RuntimeStateMachine states = new RuntimeStateMachine();
        states.rendererReady();
        states.worldStreaming(true);
        states.worldStreaming(false);
        assertEquals(RuntimeState.ACTIVE, states.current());

        states.worldChanged();
        assertEquals(RuntimeState.WAITING_FOR_WORLD, states.current());
        states.worldStreaming(false);
        assertEquals(RuntimeState.STREAMING, states.current());
        states.worldStreaming(true);
        assertEquals(RuntimeState.ACTIVE, states.current());
    }

    @Test
    void explicitDisableReturnsToVanillaAndPermitsFreshInitialization() {
        RuntimeStateMachine states = new RuntimeStateMachine();
        states.rendererReady();
        states.worldStreaming(true);
        states.disabled();
        assertEquals(RuntimeState.DISABLED, states.current());
        states.worldStreaming(true);
        states.worldAbsent();
        assertEquals(RuntimeState.DISABLED, states.current());

        states.rendererReady();
        assertEquals(RuntimeState.WAITING_FOR_WORLD, states.current());

        states.fail();
        states.disabled();
        states.rendererReady();
        assertEquals(RuntimeState.WAITING_FOR_WORLD, states.current());
    }
}
