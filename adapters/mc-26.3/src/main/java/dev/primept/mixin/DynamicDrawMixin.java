package dev.primept.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import dev.primept.capture.DynamicCapture;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StagedVertexBuffer.Draw.class)
public abstract class DynamicDrawMixin {
    @Inject(method = "append", at = @At("HEAD"))
    private void primept$capture(MeshData data, CallbackInfo callback) {
        DynamicCapture.mesh((StagedVertexBuffer.Draw) (Object) this, data);
    }
}
