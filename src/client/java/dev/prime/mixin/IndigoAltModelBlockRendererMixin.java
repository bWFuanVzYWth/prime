// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import dev.prime.render.scene.vanilla.VanillaSectionCapture;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observes Fabric Renderer API quads at Indigo's accepted-quad boundary.
 *
 * <p>Fabric redirects {@code SectionCompiler} away from Minecraft's
 * {@code ModelBlockRenderer}. Capturing only the vanilla method therefore silently loses every
 * Fabric-rendered block, including ordinary blocks while Fabric API is installed. This hook keeps
 * the renderer interface untouched and is inert outside Prime's private Section compile scope.
 */
@Mixin(AltModelBlockRendererImpl.class)
public abstract class IndigoAltModelBlockRendererMixin {
    @Inject(method = "tesselateBlock", at = @At("HEAD"))
    private void prime$beginBlockCapture(
            QuadEmitter output,
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockPos position,
            BlockState state,
            BlockStateModel model,
            long seed,
            CallbackInfo ci) {
        VanillaSectionCapture.beginFabricBlock(level, position, state, model);
    }

    @Inject(method = "tesselateBlock", at = @At("RETURN"))
    private void prime$endBlockCapture(
            QuadEmitter output,
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockPos position,
            BlockState state,
            BlockStateModel model,
            long seed,
            CallbackInfo ci) {
        VanillaSectionCapture.endFabricBlock();
    }

    @Inject(method = "transform", at = @At("HEAD"))
    private void prime$beginQuadCapture(
            MutableQuadView quad,
            CallbackInfoReturnable<Boolean> cir) {
        VanillaSectionCapture.beginFabricQuad(quad);
    }

    @Inject(method = "transform", at = @At("RETURN"))
    private void prime$finishQuadCapture(
            MutableQuadView quad,
            CallbackInfoReturnable<Boolean> cir) {
        VanillaSectionCapture.finishFabricQuad(quad, cir.getReturnValueZ());
    }
}
