// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.prime.render.scene.vanilla.DynamicSceneCapture;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionMixin {
    @Inject(method = "submitText", at = @At("HEAD"))
    private void prime$captureText(
            PoseStack poseStack,
            float x,
            float y,
            FormattedCharSequence text,
            boolean dropShadow,
            Font.DisplayMode displayMode,
            int lightCoords,
            int color,
            int backgroundColor,
            int outlineColor,
            CallbackInfo ci) {
        DynamicSceneCapture.captureText(
                poseStack,
                x,
                y,
                text,
                dropShadow,
                displayMode,
                lightCoords,
                color,
                backgroundColor,
                outlineColor);
    }

    @Inject(method = "submitMovingBlock", at = @At("HEAD"))
    private void prime$captureMovingBlock(
            PoseStack poseStack,
            MovingBlockRenderState state,
            int outlineColor,
            CallbackInfo ci) {
        DynamicSceneCapture.captureMovingBlock(poseStack, state);
    }

    @Inject(method = "submitFlame", at = @At("HEAD"))
    private void prime$captureFlame(
            PoseStack poseStack,
            EntityRenderState state,
            Quaternionf rotation,
            CallbackInfo ci) {
        DynamicSceneCapture.captureFlame(poseStack, state, rotation);
    }

    @Inject(method = "submitLeash", at = @At("HEAD"))
    private void prime$captureLeash(
            PoseStack poseStack,
            EntityRenderState.LeashState state,
            CallbackInfo ci) {
        DynamicSceneCapture.captureLeash(poseStack, state);
    }

    @Inject(method = "submitModel", at = @At("HEAD"))
    private <S> void prime$captureModel(
            Model<? super S> model,
            S state,
            PoseStack poseStack,
            RenderType renderType,
            int lightCoords,
            int overlayCoords,
            int tintedColor,
            @Nullable TextureAtlasSprite sprite,
            int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
            CallbackInfo ci) {
        DynamicSceneCapture.captureModel(
                model,
                state,
                poseStack,
                renderType,
                lightCoords,
                overlayCoords,
                tintedColor,
                sprite,
                outlineColor,
                crumblingOverlay);
    }

    @Inject(method = "submitBlockModel", at = @At("HEAD"))
    private void prime$captureBlockModel(
            PoseStack poseStack,
            RenderType renderType,
            List<BlockStateModelPart> modelParts,
            int[] tintLayers,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            CallbackInfo ci) {
        DynamicSceneCapture.captureBlockModel(
                poseStack,
                renderType,
                modelParts,
                tintLayers,
                lightCoords,
                overlayCoords,
                outlineColor);
    }

    @Inject(method = "submitItem", at = @At("HEAD"))
    private void prime$captureItem(
            PoseStack poseStack,
            ItemDisplayContext displayContext,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            int[] tintLayers,
            List<BakedQuad> quads,
            ItemStackRenderState.FoilType foilType,
            CallbackInfo ci) {
        DynamicSceneCapture.captureItem(
                poseStack,
                displayContext,
                lightCoords,
                overlayCoords,
                outlineColor,
                tintLayers,
                quads,
                foilType);
    }

    @Inject(method = "submitCustomGeometry", at = @At("HEAD"))
    private void prime$captureCustomGeometry(
            PoseStack poseStack,
            RenderType renderType,
            SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer,
            CallbackInfo ci) {
        DynamicSceneCapture.captureCustomGeometry(
                poseStack, renderType, customGeometryRenderer);
    }

    @Inject(method = "submitQuadParticleGroup", at = @At("HEAD"))
    private void prime$captureParticles(
            QuadParticleRenderState particles, CallbackInfo ci) {
        DynamicSceneCapture.captureParticles(particles);
    }
}
