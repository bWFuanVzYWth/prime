package dev.primept.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.primept.capture.CachedBlockGeometry;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Same submit semantics as Fabric's mesh state, without rebuilding/copying immutable resource geometry. */
@Mixin(value = BlockModelRenderState.class, priority = 1100)
public abstract class BlockGeometryStateMixin implements CachedBlockGeometry {
    @Shadow private List<BlockStateModelPart> modelParts;
    @Shadow private Matrix4fc transformation;
    @Shadow private RenderType renderType;
    @Shadow private IntList tintLayers;
    @Shadow public int blockLightCoords;
    @Unique private Mesh primept$cachedMesh;

    @Override
    public void primept$geometry(Mesh mesh, Matrix4fc transform, boolean translucent) {
        primept$cachedMesh = mesh;
        transformation = transform;
        renderType =
                translucent ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet();
        modelParts = null;
    }
    @Inject(method = "clear", at = @At("HEAD"))
    private void primept$clear(CallbackInfo callback) {
        primept$cachedMesh = null;
    }
    @Inject(method = "setupModel", at = @At("HEAD"))
    private void primept$ordinaryModel(CallbackInfoReturnable<List<BlockStateModelPart>> callback) {
        primept$cachedMesh = null;
    }
    @Inject(method = "setupMesh", at = @At("HEAD"), remap = false)
    private void primept$mutableMesh(CallbackInfoReturnable<?> callback) {
        primept$cachedMesh = null;
    }
    @ModifyReturnValue(method = "isEmpty", at = @At("RETURN"))
    private boolean primept$isEmpty(boolean original) {
        return original && primept$cachedMesh == null;
    }

    @Inject(method = "submitModel", at = @At("HEAD"), cancellable = true)
    private void primept$submit(RenderType type, PoseStack poses, SubmitNodeCollector collector,
                                int externalLight, int overlay, int outline,
                                CallbackInfo callback) {
        Mesh mesh = primept$cachedMesh;
        if (mesh == null)
            return;
        if (mesh.size() > 0) {
            int[] tints = tintLayers == null
                                  ? BlockModelRenderState.EMPTY_TINTS
                                  : tintLayers.toArray(BlockModelRenderState.EMPTY_TINTS);
            int light = LightCoordsUtil.max(externalLight, blockLightCoords);
            poses.pushPose();
            try {
                if (transformation != null)
                    poses.mulPose(transformation);
                collector.submitBlockModel(poses,
                                           ignored
                                           -> type,
                                           type.hasBlending(), List.of(), mesh, tints, light,
                                           overlay, outline);
            } finally {
                poses.popPose();
            }
        }
        callback.cancel();
    }
}
