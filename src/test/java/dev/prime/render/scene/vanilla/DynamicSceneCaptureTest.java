package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

final class DynamicSceneCaptureTest {
    @Test
    void capturedAtlasRetainsSrgbEncodingDespiteAttachmentUsage() {
        GpuTexture atlas = texture(15);
        GpuTexture unrelatedAttachment = texture(15);
        var captured = java.util.List.of(atlas);
        assertFalse(DynamicSceneCapture.hasUnknownColorEncoding(atlas, captured));
        assertTrue(DynamicSceneCapture.hasUnknownColorEncoding(unrelatedAttachment, captured));
        assertTrue(DynamicSceneCapture.hasUnknownColorEncoding(atlas, java.util.List.of()));
        assertFalse(DynamicSceneCapture.hasUnknownColorEncoding(texture(5), java.util.List.of()));
    }

    private static GpuTexture texture(int usage) {
        return new GpuTexture(usage, "same label", GpuFormat.RGBA8_UNORM, 16, 16, 1, 1) {
            @Override public void close() {}
            @Override public boolean isClosed() { return false; }
        };
    }

    @Test
    void preparedTextReplaysRenderableGlyphGeometry() {
        Font.PreparedText text = new Font.PreparedText() {
            @Override
            public void visit(Font.GlyphVisitor visitor) {
                visitor.acceptRenderable(new QuadTextRenderable());
            }

            @Override
            public @Nullable ScreenRectangle bounds() {
                return null;
            }
        };

        DynamicMeshBuilder builder = new DynamicMeshBuilder(0.0, 0.0, 0.0);
        DynamicTextCapture.capture(
                new Matrix4f(),
                text,
                Font.DisplayMode.NORMAL,
                0,
                (renderType, lightCoords, redAlpha) -> builder.open(
                        PrimitiveTopology.QUADS,
                        1,
                        lightCoords,
                        redAlpha));
        DynamicSceneFrame frame = builder.build(0, 0, 0, java.util.List.of());

        assertEquals(2L, frame.mesh().triangleLayout().triangleCount());
        int packed = frame.mesh().segments().getFirst().primitiveRecords()[5];
        assertTrue(dev.prime.render.terrain.PrimitivePacking.usesDynamicRedAlpha(packed));
    }

    private static final class QuadTextRenderable implements TextRenderable {
        @Override
        public void render(
                Matrix4fc pose,
                VertexConsumer buffer,
                int lightCoords,
                boolean flat) {
            vertex(buffer, pose, 0.0F, 0.0F);
            vertex(buffer, pose, 0.0F, 1.0F);
            vertex(buffer, pose, 1.0F, 1.0F);
            vertex(buffer, pose, 1.0F, 0.0F);
        }

        @Override
        public RenderType renderType(Font.DisplayMode displayMode) {
            return RenderTypes.textBackground();
        }

        @Override
        public GpuTextureView textureView() {
            throw new AssertionError("Prepared text capture resolves textures from RenderType");
        }

        @Override
        public RenderPipeline guiPipeline() {
            return RenderPipelines.GUI_TEXT_GRAYSCALE;
        }

        @Override
        public float left() {
            return 0.0F;
        }

        @Override
        public float top() {
            return 0.0F;
        }

        @Override
        public float right() {
            return 1.0F;
        }

        @Override
        public float bottom() {
            return 1.0F;
        }

        private static void vertex(
                VertexConsumer buffer, Matrix4fc pose, float x, float y) {
            buffer.addVertex(pose, x, y, 0.0F)
                    .setColor(-1)
                    .setUv(0.0F, 0.0F)
                    .setLight(0)
                    .setNormal(0.0F, 0.0F, 1.0F);
        }
    }
}
