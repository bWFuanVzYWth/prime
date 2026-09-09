// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

/** Mixin-owned stable entity identity carried by Minecraft's extracted render state. */
public interface PrimeEntityRenderState {
    int prime$entityId();

    boolean prime$hasEntityId();

    void prime$entityId(int entityId);
}
