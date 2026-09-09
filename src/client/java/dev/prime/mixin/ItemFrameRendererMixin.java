// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import dev.prime.render.scene.vanilla.ItemFrameModelFallback;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFrameRenderer.class)
public abstract class ItemFrameRendererMixin {
    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/decoration/ItemFrame;Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;F)V",
            at = @At("TAIL"))
    private void prime$restoreMissingFrameModel(
            ItemFrame itemFrame,
            ItemFrameRenderState state,
            float partialTick,
            CallbackInfo ci) {
        BlockModelRenderStateAccessor frameModel =
                (BlockModelRenderStateAccessor) state.frameModel;
        ItemFrameModelFallback.restoreIfMissing(
                state,
                frameModel.prime$modelParts(),
                frameModel.prime$specialRenderer() != null);
    }
}
