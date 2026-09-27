package dev.primept.fixture;

import com.mojang.blaze3d.platform.InputConstants;
import dev.primept.capture.SettingsCpuSmoke;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No native keyboard/window access; tests still use the host's real keys and global handler. */
@Mixin(InputConstants.class)
public abstract class InputStateMixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void test$keyState(com.mojang.blaze3d.platform.Window window, int key,
                                      CallbackInfoReturnable<Boolean> result) {
        if (SettingsCpuSmoke.inputProbe)
            result.setReturnValue(key == SettingsCpuSmoke.pressedAlt);
    }
}
