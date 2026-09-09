// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.prime.render.scene.vanilla.DynamicSceneCapture;
import dev.prime.render.scene.vanilla.PrimeEntityRenderState;
import dev.prime.render.scene.vanilla.VanillaSceneBoundary;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @WrapMethod(method = "submit")
    private <S extends EntityRenderState> void prime$captureMotionObject(
            S state,
            CameraRenderState camera,
            double x,
            double y,
            double z,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            Operation<Void> original) {
        PrimeEntityRenderState primeState = (PrimeEntityRenderState) state;
        long key = primeState.prime$entityId();
        boolean captured = DynamicSceneCapture.tryBeginMotionObject(
                VanillaSceneBoundary.Element.ENTITY,
                key,
                primeState.prime$hasEntityId());
        try {
            original.call(state, camera, x, y, z, poseStack, collector);
        } finally {
            if (captured) {
                DynamicSceneCapture.endMotionObject(
                        VanillaSceneBoundary.Element.ENTITY, key);
            }
        }
    }

    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void prime$attachEntityId(
            E entity,
            float partialTick,
            CallbackInfoReturnable<EntityRenderState> callback) {
        ((PrimeEntityRenderState) callback.getReturnValue())
                .prime$entityId(entity.getId());
    }
}
