package dev.primept.capture;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** Tests transformed source drain and incremental scheduling before any device/window initialization. */
final class ExclusiveTerrainCpuSmoke {
    static void run() throws Exception {
        for (String target :
             List.of("net.minecraft.client.renderer.GameRenderer",
                     "net.minecraft.client.renderer.LevelRenderer",
                     "net.minecraft.client.renderer.extract.LevelExtractor",
                     "net.minecraft.client.multiplayer.ClientChunkCache",
                     "net.minecraft.client.renderer.feature.FeatureRenderDispatcher"))
            Class.forName(target, false, ExclusiveTerrainCpuSmoke.class.getClassLoader());
        TerrainLeaseCpuSmoke.run();
        TerrainRestoreCpuSmoke.run();
        BlockEntityCandidatesCpuSmoke.run();
        TerrainEmptyCpuSmoke.run();
        TerrainRouterCpuSmoke.run();
        TerrainRequestsCpuSmoke.run();
        ParticleRouterCpuSmoke.run();
        var changes = new SectionChanges();
        long a = SectionPos.asLong(1, 4, 2), b = SectionPos.asLong(1, 5, 2),
             c = SectionPos.asLong(3, 4, 2);
        changes.add(a);
        changes.add(b);
        changes.add(a);
        changes.add(c);
        long[] sealed = changes.seal();
        check(java.util.Arrays.equals(sealed, new long[] {a, b, c}),
              "Finite deduplicated batch in source order");
        changes.add(a);
        check(changes.size() == 1 && sealed.length == 3, "Reentrant dirty belongs to next batch");
        changes.awaitSource(a);
        check(changes.size() == 1 && changes.waitingSize() == 0,
              "Deferring old work cannot consume a newer dirty observation");
        changes.awaitSource(b);
        check(changes.size() == 1 && changes.waitingSize() == 1,
              "Readiness waiting is separate from active dirty work");
        changes.add(b);
        check(changes.waitingSize() == 0, "New dirtiness wakes only its own blocked source");
        changes.add(c);
        changes.removeChunk(1, 2, -4, 19);
        check(java.util.Arrays.equals(changes.seal(), new long[] {c}),
              "Only unloaded work is withdrawn");
        for (int i = 0; i < 262145; ++i)
            changes.add(i);
        check(changes.seal().length == 262145 && changes.isEmpty(), "No historical queue quota");

        var camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        try (var staged = new StagedVertexBuffer(() -> "exclusive CPU drain", 1024)) {
            for (int frame = 0; frame < 2; ++frame) {
                DynamicCapture.begin(camera);
                try {
                    var first =
                            staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS);
                    var last =
                            staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS);
                    Map<StagedVertexBuffer.Draw, DynamicCapture.Material> materials =
                            field(DynamicCapture.class, "DRAWS", null);
                    materials.put(first, new DynamicCapture.Material(0, 0, false, true));
                    materials.put(last, new DynamicCapture.Material(0, 0, false, true));
                    quad(staged.getVertexBuilder(first), frame);
                    quad(staged.getVertexBuilder(last), frame + 2);
                    check(DynamicCapture.stats().vertices() == 4,
                          "Only the first builder has been observed before drain");
                    ((CaptureStagedBuffer)staged).primept$drainCapturedSource();
                    check(DynamicCapture.healthy() && DynamicCapture.stats().vertices() == 8,
                          "Last real builder is captured exactly once before release");
                    check(((List<?>)field(StagedVertexBuffer.Draw.class, "vertexBufferSlices",
                                          first))
                                          .isEmpty() &&
                                  ((List<?>)field(StagedVertexBuffer.Draw.class,
                                                  "vertexBufferSlices", last))
                                          .isEmpty(),
                          "All retained source pages freed");
                    check(field(StagedVertexBuffer.class, "lastVertexBuilder", staged) == null,
                          "No unfinished builder survives drain");
                    check(field(StagedVertexBuffer.class, "currentVertexBuffer", staged) == null &&
                                  field(StagedVertexBuffer.class, "currentIndexBuffer", staged) ==
                                          null,
                          "No raster GPU upload buffers created");
                    staged.endDraw();
                    check(((List<?>)field(StagedVertexBuffer.class, "draws", staged)).isEmpty(),
                          "Real endDraw removes frame metadata");
                } finally {
                    DynamicCapture.end();
                }
            }
        }
        DynamicCapture.close();
        System.out.println(
                "PRIME_PT_EXCLUSIVE_CPU_OK: sealed source changes; reentrant dirty/unload/epoch; actual 2-frame last-builder capture and source-page retirement without GPU upload");
    }
    private static void quad(com.mojang.blaze3d.vertex.VertexConsumer out, float offset) {
        for (int i = 0; i < 4; ++i)
            out.addVertex(offset + (i & 1), 0, i >> 1)
                    .setColor(-1)
                    .setUv(0, 0)
                    .setOverlay(0)
                    .setLight(0)
                    .setNormal(0, 1, 0);
    }
    @SuppressWarnings("unchecked")
    private static <T> T field(Class<?> type, String name, Object target) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (T)field.get(target);
    }
    private static void check(boolean pass, String message) {
        if (!pass)
            throw new AssertionError(message);
    }
}
