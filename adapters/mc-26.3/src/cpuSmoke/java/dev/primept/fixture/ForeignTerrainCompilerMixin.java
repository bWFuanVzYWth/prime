package dev.primept.fixture;

import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionCompiler.class)
public abstract class ForeignTerrainCompilerMixin {
    @Inject(method = "compile", at = @At("HEAD"))
    private void foreign$compile(CallbackInfoReturnable<SectionCompiler.Results> callback) {
        ++dev.primept.capture.ForeignHookProbe.terrainCalls;
    }
}
