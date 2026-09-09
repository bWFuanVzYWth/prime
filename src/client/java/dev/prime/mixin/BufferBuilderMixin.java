// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import dev.prime.render.scene.vanilla.VanillaSectionCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin {
    @Inject(method = "addVertex(FFFIFFIIFFF)V", at = @At("HEAD"))
    private void prime$captureVanillaFluidVertex(
            float x,
            float y,
            float z,
            int color,
            float u,
            float v,
            int overlay,
            int light,
            float normalX,
            float normalY,
            float normalZ,
            CallbackInfo ci) {
        VanillaSectionCapture.recordFluidVertex(
                x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
    }
}
