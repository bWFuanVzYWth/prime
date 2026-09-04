package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import dev.prime.render.shader.ShaderAbi;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.data.AtlasIds;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Render-thread boundary around Minecraft's dynamic world submissions.
 *
 * <p>Minecraft remains responsible for model selection, animation and transforms. Prime only
 * replays accepted mesh submissions into a CPU triangle sink while the corresponding world
 * element scope is active.
 */
public final class DynamicSceneCapture {
    private static final ThreadLocal<Session> ACTIVE = new ThreadLocal<>();
    private static final Direction[] DIRECTIONS = Direction.values();

    private DynamicSceneCapture() {
    }

    public static void begin(Vec3 cameraPosition) {
        if (ACTIVE.get() != null) {
            // A failed vanilla frame may bypass RETURN injections. The next frame owns a fresh
            // capture and must not inherit its partial model state.
            ACTIVE.remove();
        }
        ACTIVE.set(new Session(cameraPosition));
    }

    public static boolean active() {
        return ACTIVE.get() != null;
    }

    public static void reportCompatibilityIssue(
            DynamicSceneFrame.CompatibilityIssue issue) {
        Session session = ACTIVE.get();
        if (session != null) {
            session.builder.report(issue);
        }
    }

    public static void beginElement(VanillaSceneBoundary.Element element) {
        Session session = ACTIVE.get();
        if (session != null) {
            session.beginElement(element);
        }
    }

    public static void endElement(VanillaSceneBoundary.Element element) {
        Session session = ACTIVE.get();
        if (session != null) {
            session.endElement(element);
        }
    }

    public static DynamicSceneFrame finish() {
        Session session = ACTIVE.get();
        if (session == null) {
            throw new IllegalStateException("Dynamic scene capture was not opened");
        }
        ACTIVE.remove();
        return session.finish();
    }

    public static <S> void captureModel(
            Model<? super S> model,
            S state,
            PoseStack poseStack,
            RenderType renderType,
            int lightCoords,
            int overlayCoords,
            int tintedColor,
            @Nullable TextureAtlasSprite sprite,
            int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        Session session = activeSession();
        if (session == null || renderType.isOutline()) {
            return;
        }
        DynamicMeshBuilder.VertexSink sink = session.open(renderType, lightCoords);
        if (sink == null) {
            return;
        }
        var consumer = sprite == null ? sink : sprite.wrap(sink);
        PoseStack capturePose = new PoseStack();
        capturePose.last().set(poseStack.last());
        model.setupAnim(state);
        model.renderToBuffer(
                capturePose, consumer, lightCoords, overlayCoords, tintedColor);
        sink.finish();
    }

    public static void captureBlockModel(
            PoseStack poseStack,
            RenderType renderType,
            List<BlockStateModelPart> modelParts,
            int[] tintLayers,
            int lightCoords,
            int overlayCoords,
            int outlineColor) {
        Session session = activeSession();
        if (session == null || renderType.isOutline()) {
            return;
        }
        DynamicMeshBuilder.VertexSink sink = session.open(renderType, lightCoords);
        if (sink == null) {
            return;
        }
        QuadInstance instance = new QuadInstance();
        instance.setLightCoords(lightCoords);
        instance.setOverlayCoords(overlayCoords);
        for (BlockStateModelPart part : modelParts) {
            for (Direction direction : DIRECTIONS) {
                captureBlockQuads(
                        poseStack,
                        part.getQuads(direction),
                        tintLayers,
                        instance,
                        sink);
            }
            captureBlockQuads(
                    poseStack, part.getQuads(null), tintLayers, instance, sink);
        }
        sink.finish();
    }

    public static void captureMovingBlock(
            PoseStack poseStack, MovingBlockRenderState state) {
        Session session = activeSession();
        if (session == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        BlockState blockState = state.blockState;
        BlockStateModel model = minecraft.getModelManager()
                .getBlockStateModelSet()
                .get(blockState);
        ModelBlockRenderer renderer = new ModelBlockRenderer(
                minecraft.options.ambientOcclusion().get(),
                false,
                minecraft.getBlockColors());
        PoseStack capturePose = copyPoseStack(poseStack);
        BlockQuadOutput layered = (x, y, z, quad, instance) -> captureMovingQuad(
                session,
                capturePose,
                x,
                y,
                z,
                quad,
                instance,
                quad.materialInfo().layer());
        BlockQuadOutput solid = (x, y, z, quad, instance) -> captureMovingQuad(
                session,
                capturePose,
                x,
                y,
                z,
                quad,
                instance,
                ChunkSectionLayer.SOLID);
        BlockQuadOutput output = ModelBlockRenderer.forceOpaque(
                minecraft.options.cutoutLeaves().get(), blockState)
                ? solid
                : layered;
        renderer.tesselateBlock(
                output,
                0.0F,
                0.0F,
                0.0F,
                state,
                state.blockPos,
                blockState,
                model,
                blockState.getSeed(state.randomSeedPos));
    }

    public static void captureFlame(
            PoseStack poseStack,
            EntityRenderState state,
            Quaternionf rotation) {
        Session session = activeSession();
        if (session == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlas blockAtlas = minecraft.getAtlasManager()
                .getAtlasOrThrow(AtlasIds.BLOCKS);
        int textureIndex = session.textureIndex(
                blockAtlas.getTextureView(),
                blockAtlas.getSampler(),
                DynamicSceneFrame.Sampling.SRGB_COLOR);
        DynamicMeshBuilder.VertexSink sink = textureIndex < 0
                ? null
                : session.builder.open(
                        PrimitiveTopology.QUADS,
                        textureIndex,
                        LightCoordsUtil.withBlock(state.lightCoords, 15));
        if (sink == null) {
            return;
        }
        TextureAtlasSprite fire0 = minecraft.getAtlasManager().get(ModelBakery.FIRE_0);
        TextureAtlasSprite fire1 = minecraft.getAtlasManager().get(ModelBakery.FIRE_1);
        PoseStack.Pose pose = copyPoseStack(poseStack).last();
        float scale = state.boundingBoxWidth * 1.4F;
        pose.scale(scale, scale, scale);
        float radius = 0.5F;
        float remainingHeight = state.boundingBoxHeight / scale;
        float yOffset = 0.0F;
        pose.rotate(rotation);
        pose.translate(0.0F, 0.0F, 0.3F - (int) remainingHeight * 0.02F);
        float zOffset = 0.0F;
        int layer = 0;
        int lightCoords = LightCoordsUtil.withBlock(state.lightCoords, 15);
        while (remainingHeight > 0.0F) {
            TextureAtlasSprite sprite = (layer & 1) == 0 ? fire0 : fire1;
            float u0 = sprite.getU0();
            float v0 = sprite.getV0();
            float u1 = sprite.getU1();
            float v1 = sprite.getV1();
            if ((layer / 2 & 1) == 0) {
                float swap = u1;
                u1 = u0;
                u0 = swap;
            }
            fireVertex(pose, sink, -radius, -yOffset, zOffset, u1, v1, lightCoords);
            fireVertex(pose, sink, radius, -yOffset, zOffset, u0, v1, lightCoords);
            fireVertex(pose, sink, radius, 1.4F - yOffset, zOffset, u0, v0, lightCoords);
            fireVertex(pose, sink, -radius, 1.4F - yOffset, zOffset, u1, v0, lightCoords);
            remainingHeight -= 0.45F;
            yOffset -= 0.45F;
            radius *= 0.9F;
            zOffset -= 0.03F;
            layer++;
        }
        sink.finish();
    }

    public static void captureLeash(
            PoseStack poseStack, EntityRenderState.LeashState state) {
        Session session = activeSession();
        if (session == null) {
            return;
        }
        session.builder.markMotionObjectUnique();
        DynamicMeshBuilder.VertexSink sink = session.builder.openUntextured(
                PrimitiveTopology.TRIANGLE_STRIP, 0);
        Matrix4f pose = new Matrix4f(poseStack.last().pose());
        float dx = (float) (state.end.x - state.start.x);
        float dy = (float) (state.end.y - state.start.y);
        float dz = (float) (state.end.z - state.start.z);
        float offsetFactor = Mth.invSqrt(dx * dx + dz * dz) * 0.025F;
        float dxOffset = dz * offsetFactor;
        float dzOffset = dx * offsetFactor;
        pose.translate((float) state.offset.x, (float) state.offset.y, (float) state.offset.z);
        for (int step = 0; step <= 24; step++) {
            leashVertexPair(
                    sink, pose, dx, dy, dz, 0.05F, dxOffset, dzOffset, step, false, state);
        }
        for (int step = 24; step >= 0; step--) {
            leashVertexPair(
                    sink, pose, dx, dy, dz, 0.0F, dxOffset, dzOffset, step, true, state);
        }
        sink.finish();
    }

    public static void captureText(
            PoseStack poseStack,
            float x,
            float y,
            FormattedCharSequence text,
            boolean dropShadow,
            Font.DisplayMode displayMode,
            int lightCoords,
            int color,
            int backgroundColor,
            int outlineColor) {
        Session session = activeSession();
        if (session == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Matrix4f pose = new Matrix4f(poseStack.last().pose());
        if (outlineColor == 0) {
            capturePreparedText(
                    pose,
                    font.prepareText(
                            text,
                            x,
                            y,
                            color,
                            dropShadow,
                            false,
                            backgroundColor),
                    displayMode,
                    lightCoords);
            return;
        }
        capturePreparedText(
                pose,
                font.prepare8xTextOutline(text, x, y, outlineColor),
                Font.DisplayMode.NORMAL,
                lightCoords);
        capturePreparedText(
                pose,
                font.prepareText(text, x, y, color, false, false, 0),
                Font.DisplayMode.POLYGON_OFFSET,
                lightCoords);
    }

    public static boolean tryBeginMotionObject(
            VanillaSceneBoundary.Element element, long key) {
        return tryBeginMotionObject(element, key, true);
    }

    public static boolean tryBeginMotionObject(
            VanillaSceneBoundary.Element element,
            long key,
            boolean identityValid) {
        Session session = ACTIVE.get();
        if (session == null || session.element != element) {
            return false;
        }
        if (!identityValid) {
            session.builder.report(
                    DynamicSceneFrame.CompatibilityIssue.MISSING_MOTION_IDENTITY);
            return false;
        }
        session.beginMotionObject(element, key);
        return true;
    }

    public static void endMotionObject(
            VanillaSceneBoundary.Element element, long key) {
        Session session = ACTIVE.get();
        if (session != null) {
            session.endMotionObject(element, key);
        }
    }

    public static void captureItem(
            PoseStack poseStack,
            ItemDisplayContext displayContext,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            int[] tintLayers,
            List<BakedQuad> quads,
            ItemStackRenderState.FoilType foilType) {
        Session session = activeSession();
        if (session == null) {
            return;
        }
        QuadInstance instance = new QuadInstance();
        instance.setLightCoords(lightCoords);
        instance.setOverlayCoords(overlayCoords);
        for (BakedQuad quad : quads) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            RenderType renderType = material.itemRenderType();
            DynamicMeshBuilder.VertexSink sink = session.open(renderType, lightCoords);
            if (sink == null) {
                continue;
            }
            int tint = material.isTinted()
                    && material.tintIndex() >= 0
                    && material.tintIndex() < tintLayers.length
                    ? tintLayers[material.tintIndex()]
                    : -1;
            instance.setColor(tint);
            instance.setLightCoords(
                    LightCoordsUtil.lightCoordsWithEmission(
                            lightCoords, material.lightEmission()));
            sink.putBakedQuad(poseStack.last(), quad, instance);
            sink.finish();
        }
    }

    public static void captureCustomGeometry(
            PoseStack poseStack,
            RenderType renderType,
            SubmitNodeCollector.CustomGeometryRenderer renderer) {
        Session session = activeSession();
        if (session == null || renderType.isOutline()) {
            return;
        }
        session.builder.markMotionObjectUnique();
        DynamicMeshBuilder.VertexSink sink = session.open(renderType, 0);
        if (sink == null) {
            return;
        }
        renderer.render(poseStack.last().copy(), sink);
        sink.finish();
    }

    public static void captureParticles(QuadParticleRenderState particles) {
        Session session = activeSession();
        if (session == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        for (SingleQuadParticle.Layer layer : particles.layers()) {
            var texture = minecraft.getTextureManager()
                    .getTexture(layer.textureAtlasLocation());
            int textureIndex = session.textureIndex(
                    texture.getTextureView(),
                    texture.getSampler(),
                    DynamicSceneFrame.Sampling.SRGB_COLOR);
            if (textureIndex < 0) {
                continue;
            }
            DynamicMeshBuilder.VertexSink sink = session.builder.openParticle(
                    PrimitiveTopology.QUADS,
                    textureIndex,
                    0);
            particles.buildLayer(layer, sink);
            sink.finish();
        }
    }

    private static void captureBlockQuads(
            PoseStack poseStack,
            List<BakedQuad> quads,
            int[] tintLayers,
            QuadInstance instance,
            DynamicMeshBuilder.VertexSink sink) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            instance.setColor(
                    tintIndex >= 0 && tintIndex < tintLayers.length
                            ? ARGB.multiply(-1, tintLayers[tintIndex])
                            : -1);
            sink.putBakedQuad(poseStack.last(), quad, instance);
        }
    }

    static void capturePreparedText(
            Matrix4f pose,
            Font.PreparedText text,
            Font.DisplayMode displayMode,
            int lightCoords) {
        Session session = activeSession();
        if (session == null) {
            return;
        }
        DynamicTextCapture.capture(
                pose, text, displayMode, lightCoords, session::open);
    }

    private static void captureMovingQuad(
            Session session,
            PoseStack poseStack,
            float x,
            float y,
            float z,
            BakedQuad quad,
            QuadInstance instance,
            ChunkSectionLayer layer) {
        RenderType renderType = switch (layer) {
            case SOLID -> RenderTypes.solidMovingBlock();
            case CUTOUT -> RenderTypes.cutoutMovingBlock();
            case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
        };
        DynamicMeshBuilder.VertexSink sink = session.open(renderType, 0);
        if (sink == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        sink.putBakedQuad(poseStack.last(), quad, instance);
        poseStack.popPose();
        sink.finish();
    }

    private static void fireVertex(
            PoseStack.Pose pose,
            DynamicMeshBuilder.VertexSink sink,
            float x,
            float y,
            float z,
            float u,
            float v,
            int lightCoords) {
        sink.addVertex(pose, x, y, z)
                .setColor(-1)
                .setUv(u, v)
                .setUv1(0, 10)
                .setLight(lightCoords)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    private static void leashVertexPair(
            DynamicMeshBuilder.VertexSink sink,
            Matrix4f pose,
            float dx,
            float dy,
            float dz,
            float fudge,
            float dxOffset,
            float dzOffset,
            int step,
            boolean backwards,
            EntityRenderState.LeashState state) {
        float progress = step / 24.0F;
        int block = (int) Mth.lerp(
                progress, state.startBlockLight, state.endBlockLight);
        int sky = (int) Mth.lerp(
                progress, state.startSkyLight, state.endSkyLight);
        int lightCoords = LightCoordsUtil.pack(block, sky);
        float modifier = step % 2 == (backwards ? 1 : 0) ? 0.7F : 1.0F;
        float x = dx * progress;
        float y = state.slack
                ? dy > 0.0F
                        ? dy * progress * progress
                        : dy - dy * (1.0F - progress) * (1.0F - progress)
                : dy * progress;
        float z = dz * progress;
        sink.addVertex(pose, x - dxOffset, y + fudge, z + dzOffset)
                .setColor(0.5F * modifier, 0.4F * modifier, 0.3F * modifier, 1.0F)
                .setLight(lightCoords);
        sink.addVertex(pose, x + dxOffset, y + 0.05F - fudge, z - dzOffset)
                .setColor(0.5F * modifier, 0.4F * modifier, 0.3F * modifier, 1.0F)
                .setLight(lightCoords);
    }

    private static PoseStack copyPoseStack(PoseStack source) {
        PoseStack result = new PoseStack();
        result.last().set(source.last());
        return result;
    }

    private static @Nullable Session activeSession() {
        return ACTIVE.get();
    }

    private static final class Session {
        private final int clusterX;
        private final int clusterY;
        private final int clusterZ;
        private final DynamicMeshBuilder builder;
        private final ArrayList<DynamicSceneFrame.SceneTexture> textures =
                new ArrayList<>();
        private VanillaSceneBoundary.Element element =
                VanillaSceneBoundary.Element.FEATURE;

        private Session(Vec3 cameraPosition) {
            int sectionX = (int) Math.floor(cameraPosition.x) >> 4;
            int sectionY = (int) Math.floor(cameraPosition.y) >> 4;
            int sectionZ = (int) Math.floor(cameraPosition.z) >> 4;
            this.clusterX = sectionX & ~3;
            this.clusterY = sectionY & ~3;
            this.clusterZ = sectionZ & ~3;
            int originX = this.clusterX << 4;
            int originY = this.clusterY << 4;
            int originZ = this.clusterZ << 4;
            this.builder = new DynamicMeshBuilder(
                    cameraPosition.x - originX,
                    cameraPosition.y - originY,
                    cameraPosition.z - originZ);
        }

        private void beginElement(VanillaSceneBoundary.Element element) {
            if (this.element != VanillaSceneBoundary.Element.FEATURE) {
                throw new IllegalStateException("Nested dynamic scene element capture");
            }
            if (element != VanillaSceneBoundary.Element.ENTITY
                    && element != VanillaSceneBoundary.Element.BLOCK_ENTITY
                    && element != VanillaSceneBoundary.Element.PARTICLE) {
                throw new IllegalArgumentException(
                        "Unsupported dynamic scene element " + element);
            }
            this.element = element;
            this.builder.captureElement(element);
        }

        private void endElement(VanillaSceneBoundary.Element element) {
            if (this.element != element) {
                throw new IllegalStateException(
                        "Dynamic scene element capture closed out of order");
            }
            this.element = VanillaSceneBoundary.Element.FEATURE;
            this.builder.captureElement(VanillaSceneBoundary.Element.FEATURE);
        }

        private void beginMotionObject(
                VanillaSceneBoundary.Element element, long key) {
            if (this.element != element) {
                throw new IllegalStateException(
                        "Dynamic motion object opened outside its element scope");
            }
            this.builder.beginMotionObject(element, key);
        }

        private void endMotionObject(
                VanillaSceneBoundary.Element element, long key) {
            if (this.element != element) {
                throw new IllegalStateException(
                        "Dynamic motion object closed outside its element scope");
            }
            this.builder.endMotionObject(element, key);
        }

        private int textureIndex(
                RenderType renderType,
                DynamicSceneFrame.Sampling sampling) {
            if (renderType.hasBlending()) {
                this.builder.report(
                        DynamicSceneFrame.CompatibilityIssue.BLENDED_MATERIAL_APPROXIMATED);
            }
            PreparedRenderType prepared = renderType.prepare();
            PreparedRenderType.Texture albedo = null;
            for (PreparedRenderType.Texture texture : prepared.textures()) {
                if ("Sampler0".equals(texture.name())) {
                    albedo = texture;
                    break;
                }
            }
            if (albedo != null) {
                return this.textureIndex(
                        albedo.textureView(), albedo.sampler(), sampling);
            }
            this.builder.report(prepared.textures().isEmpty()
                    ? DynamicSceneFrame.CompatibilityIssue.TEXTURELESS_MATERIAL_APPROXIMATED
                    : DynamicSceneFrame.CompatibilityIssue.MISSING_ALBEDO_TEXTURE);
            return 0;
        }

        private DynamicMeshBuilder.@Nullable VertexSink open(
                RenderType renderType, int fallbackLight) {
            return this.open(renderType, fallbackLight, false);
        }

        private DynamicMeshBuilder.@Nullable VertexSink open(
                RenderType renderType,
                int fallbackLight,
                boolean redAlpha) {
            DynamicSceneFrame.Sampling sampling = redAlpha
                    ? DynamicSceneFrame.Sampling.LINEAR_RED_DATA
                    : DynamicSceneFrame.Sampling.SRGB_COLOR;
            int textureIndex = this.textureIndex(renderType, sampling);
            if (textureIndex < 0) {
                return null;
            }
            return textureIndex == 0
                    ? this.builder.openUntextured(
                            renderType.primitiveTopology(),
                            fallbackLight)
                    : this.builder.open(
                            renderType.primitiveTopology(),
                            textureIndex,
                            fallbackLight,
                            redAlpha);
        }

        private int textureIndex(
                GpuTextureView view,
                GpuSampler sampler,
                DynamicSceneFrame.Sampling sampling) {
            GpuTexture texture = view.texture();
            if (sampling == DynamicSceneFrame.Sampling.SRGB_COLOR) {
                if ((texture.usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
                    this.builder.report(
                            DynamicSceneFrame.CompatibilityIssue.UNKNOWN_ALBEDO_ENCODING);
                    return 0;
                }
                if (texture.getFormat() != GpuFormat.RGBA8_UNORM
                        || texture.getDepthOrLayers() != 1
                        || (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) {
                    this.builder.report(
                            DynamicSceneFrame.CompatibilityIssue.UNSUPPORTED_ALBEDO_FORMAT);
                    return 0;
                }
            }
            for (int index = 0; index < this.textures.size(); index++) {
                DynamicSceneFrame.SceneTexture candidate = this.textures.get(index);
                if (candidate.view() == view
                        && candidate.sampler() == sampler
                        && candidate.sampling() == sampling) {
                    return index + 1;
                }
            }
            if (this.textures.size() + 1 >= ShaderAbi.SCENE_TEXTURE_COUNT) {
                this.builder.report(
                        DynamicSceneFrame.CompatibilityIssue.SCENE_TEXTURE_LIMIT);
                return 0;
            }
            this.textures.add(new DynamicSceneFrame.SceneTexture(view, sampler, sampling));
            return this.textures.size();
        }

        private DynamicSceneFrame finish() {
            if (this.element != VanillaSceneBoundary.Element.FEATURE) {
                throw new IllegalStateException(
                        "Dynamic scene capture ended inside an element");
            }
            return this.builder.build(
                    this.clusterX,
                    this.clusterY,
                    this.clusterZ,
                    this.textures);
        }
    }
}
