// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import dev.prime.render.scene.vanilla.VanillaSectionCapture;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
    @Inject(
            method = "putQuadWithTint",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockQuadOutput;put(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"))
    private void prime$captureAcceptedVanillaQuad(
            BlockQuadOutput output,
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockState state,
            BlockPos position,
            BakedQuad quad,
            CallbackInfo ci) {
        VanillaSectionCapture.recordBlockQuad(x, y, z, level, state, position, quad);
    }

    @Inject(method = "getTintColor", at = @At("RETURN"))
    private void prime$captureComputedVanillaTint(
            BlockAndTintGetter level,
            BlockState state,
            BlockPos position,
            int tintIndex,
            CallbackInfoReturnable<Integer> cir) {
        VanillaSectionCapture.recordBlockTint(position, tintIndex, cir.getReturnValueI());
    }
}
