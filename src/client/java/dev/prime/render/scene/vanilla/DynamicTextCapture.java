// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/** Replays Minecraft-prepared text without duplicating shaping, styling, or glyph selection. */
final class DynamicTextCapture {
    private DynamicTextCapture() {
    }

    static void capture(
            Matrix4f pose,
            Font.PreparedText text,
            Font.DisplayMode displayMode,
            int lightCoords,
            SinkProvider sinkProvider) {
        Map<RenderType, DynamicMeshBuilder.VertexSink> sinks =
                new LinkedHashMap<>();
        text.visit(new Font.GlyphVisitor() {
            @Override
            public void acceptRenderable(TextRenderable renderable) {
                RenderType renderType = renderable.renderType(displayMode);
                DynamicMeshBuilder.VertexSink sink = sinks.get(renderType);
                if (sink == null) {
                    boolean redAlpha = renderable.guiPipeline()
                            == RenderPipelines.GUI_TEXT_GRAYSCALE;
                    sink = sinkProvider.open(renderType, lightCoords, redAlpha);
                    if (sink != null) {
                        sinks.put(renderType, sink);
                    }
                }
                if (sink != null) {
                    renderable.render(pose, sink, lightCoords, false);
                }
            }
        });
        for (DynamicMeshBuilder.VertexSink sink : sinks.values()) {
            sink.finish();
        }
    }

    @FunctionalInterface
    interface SinkProvider {
        DynamicMeshBuilder.@Nullable VertexSink open(
                RenderType renderType, int lightCoords, boolean redAlpha);
    }
}
