// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin.streamline.reflex;

import dev.prime.streamline.StreamlineReflex;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class StreamlineReflexMouseMixin {
    @Inject(
            method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
            at = @At("HEAD"))
    private void prime$reflexTriggerFlash(long handle, MouseButtonInfo rawButtonInfo, int action, CallbackInfo ci) {
        if (!StreamlineReflex.pclAvailable()) {
            return;
        }
        if (rawButtonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT && action == InputConstants.PRESS) {
            StreamlineReflex.onTriggerFlash();
        }
    }
}
