package dev.primept.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import dev.primept.PrimeClient;
import dev.primept.capture.DynamicCapture;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(QuadParticleFeatureRenderer.class)
public abstract class ParticleCaptureMixin {
    @Inject(method = "prepareGroup",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/renderer/StagedVertexBuffer;getVertexBuilder(Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private void
    primept$particle(CallbackInfo callback, @Local SingleQuadParticle.Layer layer,
                     @Local StagedVertexBuffer.Draw draw) {
        DynamicCapture.particle(draw, layer);
    }
    @WrapOperation(
            method = "prepareGroup",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;buildLayer(Lnet/minecraft/client/particle/SingleQuadParticle$Layer;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"))
    private void
    primept$route(QuadParticleRenderState state, SingleQuadParticle.Layer layer,
                  VertexConsumer consumer, Operation<Void> original) {
        if (!PrimeClient.exclusiveFrameReady() || !DynamicCapture.particles(state, layer, consumer))
            original.call(state, layer, consumer);
    }
}
