package dev.primept.mixin;

import dev.primept.capture.TerrainCapture;
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
    @Inject(method = "putQuadWithTint", at = @At("HEAD"))
    private void primept$beginQuad(BlockQuadOutput output, float x, float y, float z,
                                   BlockAndTintGetter level, BlockState state, BlockPos position,
                                   BakedQuad quad, CallbackInfo callback) {
        TerrainCapture.beginVanilla(position, quad.materialInfo().tintIndex());
    }

    @Inject(method = "getTintColor", at = @At("RETURN"))
    private void primept$observeTint(BlockAndTintGetter level, BlockState state, BlockPos position,
                                     int tintIndex, CallbackInfoReturnable<Integer> callback) {
        TerrainCapture.vanillaTint(position, tintIndex, callback.getReturnValueI());
    }

    @Inject(method = "putQuadWithTint",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/renderer/block/BlockQuadOutput;put(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"))
    private void
    primept$acceptedQuad(BlockQuadOutput output, float x, float y, float z,
                         BlockAndTintGetter level, BlockState state, BlockPos position,
                         BakedQuad quad, CallbackInfo callback) {
        // QuadInstance already contains lighting; immutable BakedQuad and the observed tint do not.
        TerrainCapture.vanillaQuad(x, y, z, state, quad);
    }
}
