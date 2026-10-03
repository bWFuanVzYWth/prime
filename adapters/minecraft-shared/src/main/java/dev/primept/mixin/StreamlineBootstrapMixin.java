package dev.primept.mixin;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import dev.primept.StreamlineBootstrap;
import java.io.IOException;
import org.lwjgl.system.Configuration;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NativeLibrariesBootstrap.class)
public abstract class StreamlineBootstrapMixin {
    @Inject(method = "loadLibraries", at = @At("HEAD"))
    private static void primept$streamline(CallbackInfo callback) {
        try {
            StreamlineBootstrap.install(Configuration.VULKAN_LIBRARY_NAME::set);
        } catch (IOException | RuntimeException | LinkageError failure) {
            LoggerFactory.getLogger("PrimePT").warn(
                    "Early Streamline Vulkan initialization unavailable; frame generation is disabled",
                    failure);
        }
    }
}
