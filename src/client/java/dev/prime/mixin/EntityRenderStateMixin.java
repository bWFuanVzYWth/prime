// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import dev.prime.render.scene.vanilla.PrimeEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements PrimeEntityRenderState {
    @Unique private int prime$entityId;
    @Unique private boolean prime$hasEntityId;

    @Override
    public int prime$entityId() {
        return this.prime$entityId;
    }

    @Override
    public boolean prime$hasEntityId() {
        return this.prime$hasEntityId;
    }

    @Override
    public void prime$entityId(int entityId) {
        this.prime$entityId = entityId;
        this.prime$hasEntityId = true;
    }
}
