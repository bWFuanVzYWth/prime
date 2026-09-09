// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.prime.render.scene.vanilla.VanillaSectionCapture;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
    @Shadow
    @Final
    public FluidStateModelSet fluidModels;

    @WrapMethod(method = "tesselate")
    private void prime$captureFluidTessellation(
            BlockAndTintGetter level,
            BlockPos position,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState,
            Operation<Void> original) {
        FluidModel model = this.fluidModels.get(fluidState);
        VanillaSectionCapture.beginFluid(level, position, blockState, fluidState, model);
        try {
            original.call(level, position, output, blockState, fluidState);
        } finally {
            VanillaSectionCapture.endFluid();
        }
    }
}
