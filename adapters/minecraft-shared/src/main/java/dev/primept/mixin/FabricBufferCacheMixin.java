package dev.primept.mixin;

import dev.primept.capture.FabricBufferAccess;
import dev.primept.capture.FabricMeshCapture;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets =
               "net.fabricmc.fabric.impl.client.indigo.renderer.render.ExtendedBlockModelFeatureRenderer$BufferCache")
public abstract class FabricBufferCacheMixin implements FabricBufferAccess {
    @Invoker("getBuffer") public abstract VertexConsumer primept$buffer(ChunkSectionLayer layer);
    @Inject(method = "prepare", at = @At("RETURN"))
    private void primept$prepare(CallbackInfo callback) {
        FabricMeshCapture.binding(this);
    }
    @Inject(method = "clear", at = @At("RETURN"))
    private void primept$clear(CallbackInfo callback) {
        FabricMeshCapture.binding(null);
    }
}
