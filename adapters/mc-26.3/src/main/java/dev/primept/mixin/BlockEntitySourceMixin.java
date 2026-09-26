package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.primept.capture.ModelCapture;
import dev.primept.capture.DynamicCapture;
import dev.primept.capture.SourceIdentity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntitySourceMixin {
    @Inject(method = "tryExtractRenderState", at = @At("RETURN"))
    private <E extends BlockEntity, S extends BlockEntityRenderState> void
    primept$identity(E entity, float partial, ModelFeatureRenderer.CrumblingOverlay overlay,
                     boolean flag, CallbackInfoReturnable<S> callback) {
        if (callback.getReturnValue() != null)
            ((SourceIdentity)callback.getReturnValue()).primept$source(entity);
    }
    @WrapMethod(method = "submit")
    private <S extends BlockEntityRenderState> void
    primept$source(S state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera,
                   Operation<Void> original) {
        if (!DynamicCapture.active()) {
            original.call(state, pose, collector, camera);
            return;
        }
        var pos = state.blockPos;
        var previous =
                ModelCapture.beginSource(((SourceIdentity)state).primept$source(), pos.getX(),
                                         pos.getY(), pos.getZ(), pose.last().pose(), true);
        try {
            original.call(state, pose, collector, camera);
        } finally {
            ModelCapture.endSource(previous);
        }
    }
}
