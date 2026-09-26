package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.primept.PrimeClient;
import dev.primept.capture.TerrainCapture;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Shadow @Final private boolean cutoutLeaves;

    @WrapMethod(method = "compile")
    private SectionCompiler.Results
    primept$capture(SectionPos section, RenderSectionRegion region, VertexSorting sorting,
                    SectionBufferBuilderPack buffers, Operation<SectionCompiler.Results> original) {
        try (var capture = TerrainCapture.open(PrimeClient.CAPTURE, section, cutoutLeaves)) {
            var result = original.call(section, region, sorting, buffers);
            capture.publish();
            return result;
        }
    }
}
