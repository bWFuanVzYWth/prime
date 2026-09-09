// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** Preserves entity render rules while removing the camera-frustum dependency from ray tracing. */
public final class PrimeEntityFrustum extends Frustum {
    public static final PrimeEntityFrustum INSTANCE = new PrimeEntityFrustum();

    private PrimeEntityFrustum() {
        super(new Matrix4f(), new Matrix4f());
    }

    @Override
    public boolean isVisible(AABB bounds) {
        return true;
    }
}
