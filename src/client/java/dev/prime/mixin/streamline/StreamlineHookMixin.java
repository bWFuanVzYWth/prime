package dev.prime.mixin.streamline;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import dev.prime.PrimeClient;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.vulkan.natives.NativeLibraries;
import org.lwjgl.system.Configuration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NativeLibrariesBootstrap.class)
public class StreamlineHookMixin {
    @Inject(method = "loadLibraries", at = @At("HEAD"))
    private static void loadStreamline(CallbackInfo ci) {
        if (!NativeLibraries.isWindowsX64()) {
            return;
        }
        try {
            var interposer = NativeLibraries.NATIVE_STREAMLINE_INTERPOSER.tryToExtract();
            if (PrimeClient.initializeStreamline(interposer)) {
                Configuration.VULKAN_LIBRARY_NAME.set(
                        interposer.toAbsolutePath().toString());
            }
        } catch (RuntimeException | LinkageError failure) {
            PrimeClient.shutdownStreamline();
            PrimeInfo.LOGGER.warn(
                    "Unable to install the optional Streamline Vulkan interposer; using the standard Vulkan loader",
                    failure);
        }
    }
}
