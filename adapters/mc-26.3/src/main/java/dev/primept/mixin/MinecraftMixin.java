package dev.primept.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.primept.PrimeClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    // KeyboardHandler invokes this only on PRESS, before the screenshot binding and screen handling.
    @Inject(method = "handleGlobalKeyPress", at = @At("HEAD"), cancellable = true)
    private void primept$offline(InputConstants.Key key, boolean control,
                                 CallbackInfoReturnable<Boolean> callback) {
        if (PrimeClient.offlineShortcut(key, control))
            callback.setReturnValue(true);
    }
}
