package dev.prime.mixin.streamline;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import dev.prime.PrimeClient;
import dev.prime.render.vulkan.natives.NativeLibraries;
import org.lwjgl.system.Configuration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NativeLibrariesBootstrap.class)
public class StreamlineHookMixin {
    @Inject(method = "loadLibraries",at = @At("HEAD"))
    private static void loadStreamline(CallbackInfo ci){
        if (!NativeLibraries.isWindowsX64()) {
            return;
        }
        Configuration.VULKAN_LIBRARY_NAME.set(NativeLibraries.NATIVE_STREAMLINE_INTERPOSER.tryToExtract().toAbsolutePath().toString());
        PrimeClient.initializeStreamline();
    }
}
