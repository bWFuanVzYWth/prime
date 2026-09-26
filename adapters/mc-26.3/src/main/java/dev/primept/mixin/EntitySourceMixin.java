package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.primept.capture.ModelCapture;
import dev.primept.capture.DynamicCapture;
import dev.primept.capture.SourceIdentity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntitySourceMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void
    primept$identity(E entity, float partial, CallbackInfoReturnable<EntityRenderState> callback) {
        if (callback.getReturnValue() != null)
            ((SourceIdentity)callback.getReturnValue()).primept$source(entity);
    }
    @WrapMethod(method = "submit")
    private <S extends EntityRenderState> void
    primept$source(S state, CameraRenderState camera, double x, double y, double z, PoseStack pose,
                   SubmitNodeCollector collector, Operation<Void> original) {
        if (!DynamicCapture.active()) {
            original.call(state, camera, x, y, z, pose, collector);
            return;
        }
        // The dispatcher adds the actual render offset after this base translation.
        var base = new Matrix4f(pose.last().pose()).translate((float)x, (float)y, (float)z);
        var previous = ModelCapture.beginSource(((SourceIdentity)state).primept$source(), state.x,
                                                state.y, state.z, base, false);
        try {
            original.call(state, camera, x, y, z, pose, collector);
        } finally {
            ModelCapture.endSource(previous);
        }
    }
}
