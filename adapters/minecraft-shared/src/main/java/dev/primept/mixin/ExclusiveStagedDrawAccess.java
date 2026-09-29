package dev.primept.mixin;

import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(StagedVertexBuffer.Draw.class)
public interface ExclusiveStagedDrawAccess {
    @Invoker("freeVertexData") void primept$freeVertexData();
}
