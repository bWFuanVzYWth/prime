// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.dlss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DlssRrBootstrapTest {
    @Test
    void releasedContextRearmsInitializationWithoutIgnoringDeviceFailure() {
        DlssRrBootstrap.Initialization idle = DlssRrBootstrap.Initialization.IDLE;
        assertTrue(idle.canInitialize(true));
        assertFalse(idle.canInitialize(false));

        DlssRrBootstrap.Initialization active = idle.initialized();
        assertFalse(active.canInitialize(true));
        assertEquals(idle, active.released());
        assertThrows(IllegalStateException.class, active::initialized);
        assertThrows(IllegalStateException.class, idle::released);
    }
}
