package dev.primept.mixin;

import dev.primept.capture.SourceIdentity;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin({EntityRenderState.class, BlockEntityRenderState.class})
public abstract class SourceIdentityMixin implements SourceIdentity {
    @Unique private Object primept$source;
    public Object primept$source() {
        return primept$source;
    }
    public void primept$source(Object source) {
        primept$source = source;
    }
}
