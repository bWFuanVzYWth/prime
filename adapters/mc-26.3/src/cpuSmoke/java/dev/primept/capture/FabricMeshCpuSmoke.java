package dev.primept.capture;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.primept.mixin.BufferBuilderAccessor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.render.submit.ExtendedBlockModelSubmit;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.ExtendedBlockModelFeatureRenderer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Actual transformed Indigo renderer, buffer cache, immutable mesh and submit records; no device. */
final class FabricMeshCpuSmoke {
    private static final CameraRenderState CAMERA = new CameraRenderState();
    private static final Owner OWNER = new Owner();
    private static final RenderType TYPE = Sheets.cutoutBlockItemSheet();
    private static final ExtendedBlockModelFeatureRenderer RENDERER =
            new ExtendedBlockModelFeatureRenderer();
    private static int bindings;
    static void run() throws Exception {
        CAMERA.pos = Vec3.ZERO;
        Mesh mesh = mesh(false);
        frame(mesh, false, 0xff778899, 2);
        check(DynamicCapture.stats().vertices() == 8,
              "Original Indigo buffer result retained when not exclusive");
        frame(mesh, true, 0xff778899, 2);
        check(DynamicCapture.stats().vertices() == 0,
              "Instance path has no duplicate raw geometry");
        check(FabricMeshCapture.stats().geometryVertices() == 8, "Immutable geometry read once");
        InstanceCapture context = value(ModelCapture.class, "instances", null);
        var first = context.sealDelta();
        check(first != null && context.stats().prototypeUpserts() == 2 &&
                      context.stats().instanceUpserts() == 2,
              "Per-tint prototypes and stable source instances");
        var wire = first.asByteBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        check(wire.get(48 + 56 + 12) == (byte)0x12 && wire.get(48 + 56 + 13) == (byte)0x34,
              "Prototype stores actual unlit source channels before tint");
        int firstInstance = 48 + 2 * (56 + 96);
        check(wire.getDouble(firstInstance + 24) == 10 && wire.getFloat(firstInstance + 48) == -2 &&
                      wire.getFloat(firstInstance + 60) == 2,
              "Stable world origin and full negative nonuniform affine preserved");
        int expectedTint = net.minecraft.util.ARGB.multiply(0xff8f7f6f, 0xff778899);
        check(wire.get(firstInstance + 104) == (byte)(expectedTint >>> 16),
              "Actual integer base/layer tint reaches native instance");
        context.acknowledge();
        frame(mesh, true, 0xff778899, 2);
        check(context.sealDelta() == null && FabricMeshCapture.stats().geometryVertices() == 0,
              "Stable actual submit has zero vertex reread and zero op7 bytes");
        frame(mesh, true, 0xff224466, 3);
        check(context.sealDelta() != null && context.stats().prototypeUpserts() == 0 &&
                      context.stats().instanceUpserts() == 2,
              "Actual tint/affine changes update only instance state");
        context.acknowledge();
        frame(mesh(true), true, -1, 2, true, 2);
        check(DynamicCapture.stats().vertices() == 0 && FabricMeshCapture.stats().submits() == 1,
              "Mixed source layers route independently");
        int[] unknownCalls = {0};
        Mesh unknown = new Mesh() {
            public int size() {
                return mesh.size();
            }
            public void forEach(java.util.function.Consumer<? super net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView> action) {
                ++unknownCalls[0];
                mesh.forEach(action);
            }
            public void outputTo(QuadEmitter emitter) {
                throw new AssertionError("Downstream outputTo must not execute");
            }
        };
        frame(unknown, true, -1, 2, true, 1);
        check(unknownCalls[0] == 1 && DynamicCapture.stats().vertices() == 0,
              "Custom immutable Mesh is read once through the source contract");
        frame(mesh, true, -1, 2, false, 1);
        check(FabricMeshCapture.stats().submits() == 0,
              "Unrecognized material is not silently made into white geometry");
        DynamicCapture.close();
        cubeSkip();
        System.out.println(
                "PRIME_PT_FABRIC_INSTANCE_CPU_OK: real Indigo binding callback once, immutable reuse, source tint/affine changes, raw fallback, pure Cube skip");
    }
    private static Mesh mesh(boolean mixed) {
        var mutable = Renderer.get().mutableMesh();
        QuadEmitter e = mutable.emitter();
        e.square(Direction.UP, 0, 0, 1, 1, 0)
                .chunkLayer(ChunkSectionLayer.SOLID)
                .tintIndex(0)
                .color(0xff123456, 0xffabcdef, 0xff765432, 0xff998877)
                .emit();
        e.square(Direction.DOWN, 0, 0, 1, 1, 0)
                .chunkLayer(mixed ? ChunkSectionLayer.CUTOUT : ChunkSectionLayer.SOLID)
                .tintIndex(-1)
                .color(-1, -1, -1, -1)
                .emit();
        return mutable.immutableCopy();
    }
    private static void frame(Mesh mesh, boolean exclusive, int tint, int offset) throws Exception {
        frame(mesh, exclusive, tint, offset, true, -1);
    }
    private static void frame(Mesh mesh, boolean exclusive, int tint, int offset,
                              boolean knownMaterial, int expectedCalls) throws Exception {
        DynamicCapture.begin(CAMERA);
        try (var staged = new StagedVertexBuffer(() -> "FRAPI CPU fixture", 65536)) {
            var draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS);
            Map<StagedVertexBuffer.Draw, DynamicCapture.Material> materials =
                    value(DynamicCapture.class, "DRAWS", null);
            if (knownMaterial)
                materials.put(draw, new DynamicCapture.Material(0, 1, false, true));
            var consumer = (BufferBuilder)staged.getVertexBuilder(draw);
            Object group = group(staged, draw);
            set(RenderTypeFeatureRenderer.class, "currentGroup", RENDERER, group);
            var pose = new PoseStack();
            pose.translate(offset, 4, 6);
            pose.scale(-2, 3, .5f);
            var old = ModelCapture.beginSource(OWNER, 10, 20, 30, new Matrix4f(), true);
            bindings = 0;
            Function<ChunkSectionLayer, RenderType> binding = layer -> {
                ++bindings;
                return TYPE;
            };
            var submit = new ExtendedBlockModelSubmit(pose.last().copy(), binding, List.of(), mesh,
                                                      new int[] {tint}, 0, 0, 0xff8f7f6f, null);
            ModelCapture.endSource(old);
            check(((ModelSubmission)(Object)submit).primept$submission() != null,
                  "Actual constructor attaches source identity");
            if (exclusive) {
                Object cache =
                        value(ExtendedBlockModelFeatureRenderer.class, "bufferCache", RENDERER);
                var prepare = cache.getClass().getDeclaredMethod("prepare", Function.class,
                                                                 PoseStack.Pose.class);
                prepare.setAccessible(true);
                prepare.invoke(cache, binding, null);
                set(ExtendedBlockModelFeatureRenderer.class, "submit", RENDERER, submit);
                QuadEmitter emitter =
                        value(ExtendedBlockModelFeatureRenderer.class, "emitter", RENDERER);
                if (!FabricMeshCapture.output(submit, mesh, true))
                    mesh.outputTo(emitter);
            } else {
                var build = ExtendedBlockModelFeatureRenderer.class.getDeclaredMethod(
                        "buildGroup", FeatureFrameContext.class, List.class);
                build.setAccessible(true);
                build.invoke(RENDERER, null, List.of(submit));
            }
            int expectedBindings = expectedCalls != -1                          ? expectedCalls
                                   : FabricMeshCapture.stats().fallbacks() == 0 ? 1
                                                                                : 2;
            check(bindings == expectedBindings,
                  "Actual layer material callbacks are not replayed: " + bindings);
            if (!knownMaterial)
                check(((BufferBuilderAccessor)consumer).primept$vertices() == 0,
                      "Unsupported material never triggers downstream expansion");
            var finish = StagedVertexBuffer.class.getDeclaredMethod("finishLastVertexBuilder");
            finish.setAccessible(true);
            finish.invoke(staged);
            check(DynamicCapture.healthy() == knownMaterial,
                  "Known material routes; missing material reports explicit failure");
            if (FabricMeshCapture.stats().submits() > 0)
                check(((BufferBuilderAccessor)consumer).primept$vertices() == 0,
                      "No mechanical vertex output in exclusive instance path");
        } finally {
            DynamicCapture.end();
        }
    }
    private static Object group(StagedVertexBuffer staged, StagedVertexBuffer.Draw draw)
            throws Exception {
        Class<?> type = Class.forName(RenderTypeFeatureRenderer.class.getName() + "$Group");
        var ctor = type.getDeclaredConstructor(StagedVertexBuffer.class, boolean.class);
        ctor.setAccessible(true);
        Object group = ctor.newInstance(staged, true);
        set(type, "lastRenderType", group, TYPE);
        set(type, "lastDraw", group, draw);
        return group;
    }
    private static void cubeSkip() throws Exception {
        DynamicCapture.begin(CAMERA);
        try (var staged = new StagedVertexBuffer(() -> "pure Cube CPU fixture", 65536)) {
            var draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS);
            Map<StagedVertexBuffer.Draw, DynamicCapture.Material> materials =
                    value(DynamicCapture.class, "DRAWS", null);
            materials.put(draw, new DynamicCapture.Material(0, 0, false, true));
            var consumer = (BufferBuilder)staged.getVertexBuilder(draw);
            var pose = new PoseStack();
            var source = ModelCapture.beginSource(new Owner(), 0, 0, 0, new Matrix4f(), true);
            var submission = ModelCapture.tagSubmission();
            ModelCapture.endSource(source);
            set(ModelCapture.class, "model", null, submission);
            var cube = new net.minecraft.client.model.geom.ModelPart.Cube(
                    0, 0, 0, 0, 0, 16, 16, 16, 0, 0, 0, false, 64, 64,
                    java.util.EnumSet.allOf(Direction.class));
            var observation = ModelCapture.beforeCube(cube, pose.last(), consumer, -1);
            check(!ModelCapture.skipCube(observation, false),
                  "Non-exclusive frame cannot skip original output");
            check(ModelCapture.skipCube(observation, true),
                  "Validated pure leaf uses native instance");
            check(((BufferBuilderAccessor)consumer).primept$vertices() == 0 &&
                          ModelCapture.stats().skippedVertices() == 24,
                  "Pure leaf eliminates mechanical output without inventing raw exclusion ranges");
        } finally {
            DynamicCapture.end();
            DynamicCapture.close();
        }
    }
    @SuppressWarnings("unchecked")
    private static <T> T value(Class<?> type, String name, Object target) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (T)field.get(target);
    }
    private static void set(Class<?> type, String name, Object target, Object value)
            throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static void check(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
    private static final class Owner implements ModelSourceOwner {
        private ModelCapture.Source source;
        public ModelCapture.Source primept$modelSource() {
            return source;
        }
        public void primept$modelSource(ModelCapture.Source value) {
            source = value;
        }
    }
}
