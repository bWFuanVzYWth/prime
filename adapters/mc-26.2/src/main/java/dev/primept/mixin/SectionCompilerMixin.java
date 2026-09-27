package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.primept.capture.TerrainRasterOutput;
import org.spongepowered.asm.mixin.injection.At;
import dev.primept.PrimeClient;
import dev.primept.capture.TerrainCapture;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.BlockPos;
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
    @WrapOperation(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lcom/mojang/blaze3d/vertex/MeshData;sortQuads(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;Lcom/mojang/blaze3d/vertex/VertexSorting;)Lcom/mojang/blaze3d/vertex/MeshData$SortState;"))
    private MeshData.SortState
    primept$discardSort(MeshData mesh, ByteBufferBuilder buffer, VertexSorting sorting,
                        Operation<MeshData.SortState> original) {
        return TerrainRasterOutput.omitSort(mesh, sorting) ? null
                                                           : original.call(mesh, buffer, sorting);
    }

    @WrapOperation(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/renderer/chunk/VisGraph;setOpaque(Lnet/minecraft/core/BlockPos;)V"))
    private void
    primept$discardOccluder(VisGraph graph, BlockPos position, Operation<Void> original,
                            @Local(argsOnly = true) VertexSorting sorting) {
        if (!TerrainRasterOutput.omitVisibility(sorting))
            original.call(graph, position);
    }

    @WrapOperation(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target =
                            "Lnet/minecraft/client/renderer/chunk/VisGraph;resolve()Lnet/minecraft/client/renderer/chunk/VisibilitySet;"))
    private VisibilitySet
    primept$discardVisibility(VisGraph graph, Operation<VisibilitySet> original,
                              @Local(argsOnly = true) VertexSorting sorting) {
        // The private owner consumes only release(); no raster occlusion graph consumes this result.
        return TerrainRasterOutput.omitVisibility(sorting) ? null : original.call(graph);
    }
}
