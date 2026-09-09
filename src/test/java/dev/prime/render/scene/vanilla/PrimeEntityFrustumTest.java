// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

final class PrimeEntityFrustumTest {
    @Test
    void acceptsGeometryOutsideEveryFiniteCameraFrustum() {
        assertTrue(PrimeEntityFrustum.INSTANCE.isVisible(new AABB(
                1.0e12,
                -1.0e12,
                1.0e12,
                1.0e12 + 1.0,
                -1.0e12 + 1.0,
                1.0e12 + 1.0)));
    }
}
