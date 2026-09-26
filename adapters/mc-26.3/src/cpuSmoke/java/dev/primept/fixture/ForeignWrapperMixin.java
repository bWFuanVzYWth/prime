package dev.primept.fixture;

import net.minecraft.client.renderer.block.model.BlockStateModelWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockStateModelWrapper.class)
public abstract class ForeignWrapperMixin {
    @Inject(method = "update", at = @At("TAIL"))
    private void test$observeActualUpdate(CallbackInfo callback) {
        ++dev.primept.capture.ForeignHookProbe.calls;
    }
}
