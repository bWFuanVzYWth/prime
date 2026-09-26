package dev.primept.mixin;

import dev.primept.capture.BlockGeometryCache;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.block.model.BlockStateModelWrapper;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** This wrapper's FRAPI context is EMPTY/ZERO and always-false culling, exactly the geometry-key contract. */
@Mixin(BlockStateModelWrapper.class)
public abstract class BlockGeometryWrapperMixin {
    @Shadow @Final private BlockStateModel model;
    @Shadow @Final private Matrix4fc transformation;
    @Shadow protected abstract void updateTints(BlockModelRenderState output, BlockState state);

    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void primept$cachedGeometry(BlockModelRenderState output, BlockState state,
                                        BlockDisplayContext displayContext, long seed,
                                        CallbackInfo callback) {
        if (((Object)this).getClass() != BlockStateModelWrapper.class ||
            output.getClass() != BlockModelRenderState.class)
            return;
        if (!BlockGeometryCache.resolve(model, output, state, seed, transformation))
            return;
        updateTints(output, state);
        callback.cancel();
    }
}
