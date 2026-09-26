package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.primept.capture.FluidCapture;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
    @WrapMethod(method = "tesselate")
    private void primept$fluid(BlockAndTintGetter level, BlockPos position,
                               FluidRenderer.Output output, BlockState blockState,
                               FluidState fluidState, Operation<Void> original) {
        try (var capture = FluidCapture.open(output)) {
            original.call(level, position, capture == null ? output : capture, blockState,
                          fluidState);
        }
    }

    @ModifyExpressionValue(
            method = "tesselate",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/color/block/BlockTintSource;colorInWorld(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"))
    private int
    primept$tint(int color) {
        FluidCapture.observedTint(color);
        return color;
    }

    @WrapMethod(method = "vertex")
    private void primept$vanillaVertex(VertexConsumer consumer, float x, float y, float z,
                                       int color, float u, float v, int light,
                                       Operation<Void> original) {
        FluidCapture.vanillaVertex(true);
        try {
            original.call(consumer, x, y, z, color, u, v, light);
        } finally {
            FluidCapture.vanillaVertex(false);
        }
    }
}
