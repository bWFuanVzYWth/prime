package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.primept.PresentationFps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Changes the F3 presentation metric while keeping the host's rendering/timing getters intact. */
@Mixin(DebugEntryFps.class)
public abstract class DebugEntryFpsMixin {
    @Unique private int primept$renderedFps, primept$presentedFps;

    @WrapOperation(method = "display",
                   at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getFps()I"))
    private int
    primept$presented(Minecraft minecraft, Operation<Integer> original) {
        primept$renderedFps = original.call(minecraft);
        primept$presentedFps = PresentationFps.framesPerSecond();
        return primept$presentedFps >= 0 ? primept$presentedFps : primept$renderedFps;
    }

    @ModifyArg(
            method = "display",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;addPriorityLine(Ljava/lang/String;)V")
            ,
            index = 0)
    private String
    primept$renderCadence(String line) {
        return primept$presentedFps >= 0
                ? line + " (" +
                          Component
                                  .translatable("primept.diagnostics.rendered_fps",
                                                primept$renderedFps)
                                  .getString() +
                          ")"
                : line;
    }
}
