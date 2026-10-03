package dev.primept.fixture;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import dev.primept.capture.SettingsCpuSmoke;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only the native monitor query is replaced; the real video screen and layouts execute. */
@Mixin(Window.class)
public abstract class WindowStateMixin {
    @Inject(method = "findBestMonitor", at = @At("HEAD"), cancellable = true)
    private void test$monitor(CallbackInfoReturnable<Monitor> result) {
        if (SettingsCpuSmoke.windowProbe)
            result.setReturnValue(null);
    }
}
