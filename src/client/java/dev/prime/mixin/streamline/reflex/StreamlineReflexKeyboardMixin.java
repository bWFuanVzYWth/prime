package dev.prime.mixin.streamline.reflex;

import dev.prime.streamline.StreamlineReflex;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class StreamlineReflexKeyboardMixin {
    @Inject(
            method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void prime$reflexLatencyPing(long handle, int action, KeyEvent event, CallbackInfo ci) {
        if (!StreamlineReflex.pclAvailable() || event.key() != GLFW.GLFW_KEY_F13) {
            return;
        }
        StreamlineReflex.onLatencyPing(action == GLFW.GLFW_PRESS);
        ci.cancel();
    }
}
