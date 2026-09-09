// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.prime.render.scene.vanilla.DynamicSceneCapture;
import dev.prime.render.scene.vanilla.VanillaSceneBoundary;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @WrapMethod(method = "submit")
    private <S extends BlockEntityRenderState> void prime$captureMotionObject(
            S state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera,
            Operation<Void> original) {
        long key = state.blockPos.asLong();
        boolean captured = DynamicSceneCapture.tryBeginMotionObject(
                VanillaSceneBoundary.Element.BLOCK_ENTITY, key);
        try {
            original.call(state, poseStack, collector, camera);
        } finally {
            if (captured) {
                DynamicSceneCapture.endMotionObject(
                        VanillaSceneBoundary.Element.BLOCK_ENTITY, key);
            }
        }
    }
}
