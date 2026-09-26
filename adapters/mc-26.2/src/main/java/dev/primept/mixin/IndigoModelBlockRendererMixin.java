package dev.primept.mixin;

import dev.primept.capture.TerrainCapture;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fabric redirects ordinary section models to Indigo, so vanilla-only hooks are insufficient. */
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl")
public abstract class IndigoModelBlockRendererMixin {
    @Inject(method = "tesselateBlock", at = @At("HEAD"))
    private void primept$beginBlock(QuadEmitter output, float x, float y, float z,
                                    BlockAndTintGetter level, BlockPos position, BlockState state,
                                    BlockStateModel model, long seed, CallbackInfo callback) {
        TerrainCapture.beginFabricBlock(state);
    }

    @Inject(method = "transform", at = @At("HEAD"))
    private void primept$sourceColors(MutableQuadView quad,
                                      CallbackInfoReturnable<Boolean> callback) {
        TerrainCapture.beginFabricQuad(quad);
    }

    @Inject(method = "getTintColor", at = @At("RETURN"))
    private void primept$observeTint(BlockAndTintGetter level, BlockState state, BlockPos position,
                                     int tintIndex, CallbackInfoReturnable<Integer> callback) {
        TerrainCapture.fabricTint(callback.getReturnValueI());
    }

    @Inject(method = "transform", at = @At("RETURN"))
    private void primept$acceptedQuad(MutableQuadView quad,
                                      CallbackInfoReturnable<Boolean> callback) {
        TerrainCapture.finishFabricQuad(quad, callback.getReturnValueZ());
    }
}
