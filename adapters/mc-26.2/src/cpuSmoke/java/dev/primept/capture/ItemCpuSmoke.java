package dev.primept.capture;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.PrimitiveTopology;
import dev.primept.mixin.BufferBuilderAccessor;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState.FoilType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Real transformed ItemFeatureRenderer and draw callbacks, before any Minecraft/GPU initialization. */
public final class ItemCpuSmoke {
    public static boolean exclusive;
    public static int foreignCalls;
    private static final CameraRenderState CAMERA = new CameraRenderState();
    private static final RenderType TYPE = Sheets.cutoutBlockItemSheet();
    // The routing boundary depends on the actual source submit, not renderer identity.
    private static final ItemFeatureRenderer RENDERER = new ItemFeatureRenderer() {};
    private static final Owner FIRST = new Owner(), SECOND = new Owner();
    private static int emitted;
    private static byte[] baseline;
    public static void run() throws Exception {
        CAMERA.pos = Vec3.ZERO;
        Vector3f mutable = new Vector3f(0, 0, 0);
        var quads = List.of(quad(mutable), quad(new Vector3f(2, 0, 0)));
        frame(quads, false, true, FoilType.NONE, 0, 0xff123456, 2, 1, null);
        check(emitted == 8 && DynamicCapture.stats().vertices() == 8,
              "Nonexclusive original output retained");
        DynamicFrame raw = value(DynamicCapture.class, "frame", null);
        baseline = raw.seal().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        frame(quads, true, true, FoilType.NONE, 0, 0xff123456, 2, 2, null);
        var context = context();
        var wire = context.sealDelta().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        check(emitted == 0 && DynamicCapture.stats().vertices() == 0,
              "No duplicate original/raw output");
        check(ItemCapture.stats().groups() == 2 && context.stats().prototypeUpserts() == 1 &&
                      context.stats().instanceUpserts() == 2,
              "Whole submit batches and cross-source shared prototype");
        check(ItemCapture.stats().createdVertices() == 8 && ItemCapture.stats().sharedHits() == 1,
              "Identical content from distinct sources shares one prototype");
        int instanceOffset = 48 + 56 + 8 * 24;
        check(wire.getDouble(instanceOffset + 24) == 10 &&
                      wire.getFloat(instanceOffset + 48) == -2 &&
                      wire.getFloat(instanceOffset + 60) == 2,
              "Stable origin and negative nonuniform affine");
        check(wire.getInt(instanceOffset + 104) == 0xff563412,
              "Actual integer tint kept on instance");
        check(wire.getInt(48 + 56 + 12) == -1, "Reusable source remains untinted white");
        equivalent(wire, instanceOffset);
        context.acknowledge();
        frame(quads, true, true, FoilType.NONE, 0, 0xff123456, 2, 2, null);
        check(context.sealDelta() == null && ItemCapture.stats().createdVertices() == 0 &&
                      ItemCapture.stats().checkedVertices() == 16,
              "Stable mutable-source check emits no op7 bytes");
        frame(quads, true, true, FoilType.NONE, 0, 0xff654321, 3, 2, null);
        context.sealDelta();
        check(context.stats().prototypeUpserts() == 0 && context.stats().instanceUpserts() == 2,
              "Tint/pose changes do not recreate geometry");
        context.acknowledge();
        // Mutation between two actual submits: earlier instances keep the old geometry until the next frame.
        frame(quads, true, true, FoilType.NONE, 0, -1, 2, 2, () -> mutable.x = .5f);
        context.sealDelta();
        check(context.stats().prototypeUpserts() == 1 && context.stats().prototypeRemoves() == 0,
              "Same-frame mutation preserves old and new geometry");
        context.acknowledge();
        for (int i = 0; i < 256; ++i) {
            mutable.x = i + 1.25f;
            frame(quads, true, true, FoilType.NONE, 0, -1, 2, 2, null);
            context.sealDelta();
            check(context.stats().prototypeUpserts() == 1 &&
                          context.stats().prototypeRemoves() == (i == 0 ? 2 : 1),
                  "Continuous mutable churn retires old prototypes without GC: " + i + " " +
                          context.stats());
            context.acknowledge();
        }
        frame(List.of(), true, true, FoilType.NONE, 0, -1, 2, 2, null);
        context.sealDelta();
        check(context.stats().prototypeRemoves() == 1 && context.stats().instanceRemoves() == 2,
              "Empty submits explicitly release geometry");
        context.acknowledge();
        frame(quads, true, true, FoilType.NONE, 0, -1, 2, 1, null);
        context.sealDelta();
        context.acknowledge();
        frame(quads, true, true, FoilType.STANDARD, 0, -1, 2, 1, null);
        check(emitted == 8 && ItemCapture.stats().submits() == 0,
              "Foil retains actual original output");
        context.sealDelta();
        check(context.stats().prototypeRemoves() == 1 && context.stats().instanceRemoves() == 1,
              "Switch to raw path retires unseen scope");
        context.acknowledge();
        frame(quads, true, false, FoilType.NONE, 0, -1, 2, 1, null);
        check(emitted == 0 && ItemCapture.stats().submits() == 0,
              "Unbound/excluded material never expands or invents a surface");
        frame(quads, true, true, FoilType.NONE, 123, -1, 2, 1, null);
        check(emitted == 8 && ItemCapture.stats().submits() == 0,
              "Outline cannot enter semantic path");
        frame(quads, true, true, FoilType.NONE, 0, -1, 2, 1, null);
        context.sealDelta();
        context.acknowledge();
        frame(quads, true, true, FoilType.NONE, 0, -1, 2, 0, null);
        context.sealDelta();
        check(context.stats().prototypeRemoves() == 1 && context.stats().instanceRemoves() == 1,
              "Source disappears without leaf callback: frame lifecycle releases it");
        context.acknowledge();
        var collisionPosition = new Vector3f(.5f, 1, 0);
        var collision = List.of(quad(collisionPosition));
        frame(collision, true, true, FoilType.NONE, 0, -1, 2, 2, () -> {
            collisionPosition.x = Float.intBitsToFloat(Float.floatToRawIntBits(.5f) + 1);
            collisionPosition.y = Float.intBitsToFloat(Float.floatToRawIntBits(1f) - 31);
        });
        context.sealDelta();
        check(context.stats().prototypeUpserts() == 2 && ItemCapture.stats().createdVertices() == 8,
              "Equal 31-polynomial hash but unequal actual coordinates cannot alias");
        context.acknowledge();
        int[] getters = {0};
        Vector3f unusual = new Vector3f() {
            @Override
            public float x() {
                ++getters[0];
                return super.x();
            }
        };
        frame(List.of(quad(unusual)), true, true, FoilType.NONE, 0, -1, 2, 1, null);
        check(emitted == 0 && ItemCapture.stats().submits() == 1 && getters[0] == 1,
              "Custom source getter is read once before routing");
        cornerColors();
        unknownConsumer();
        exclusive = false;
        DynamicCapture.close();
        System.out.println(
                "PRIME_PT_ITEM_CPU_OK: actual ItemFeature leaf, multi-quad groups, cross-source sharing, mutable values, tint/affine, 256 explicit churn retirements, empty/unseen/raw fallback");
    }
    public static void foreign() throws Exception {
        CAMERA.pos = Vec3.ZERO;
        frame(List.of(quad(new Vector3f())), true, true, FoilType.NONE, 0, -1, 2, 1, null);
        check(foreignCalls == 1 && emitted == 0 && ItemCapture.stats().submits() == 1,
              "Foreign source callback executes once without downstream expansion");
        exclusive = false;
        DynamicCapture.close();
        System.out.println(
                "PRIME_PT_ITEM_FOREIGN_CPU_OK: source callback once, downstream expansion skipped");
    }
    private static BakedQuad quad(Vector3f position) {
        var material = new BakedQuad.MaterialInfo(null, ChunkSectionLayer.CUTOUT, TYPE, 0, true, 0);
        return new BakedQuad(position, new Vector3f(1, 0, 0), new Vector3f(1, 1, 0),
                             new Vector3f(0, 1, 0), UVPair.pack(.1f, .2f), UVPair.pack(.3f, .4f),
                             UVPair.pack(.5f, .6f), UVPair.pack(.7f, .8f), Direction.SOUTH,
                             material);
    }
    private static void frame(List<BakedQuad> quads, boolean skip, boolean known, FoilType foil,
                              int outline, int tint, int offset, int sources, Runnable between)
            throws Exception {
        exclusive = skip;
        emitted = 0;
        DynamicCapture.begin(CAMERA);
        try (var staged = new StagedVertexBuffer(() -> "Item CPU fixture", 65536)) {
            var draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS);
            Map<StagedVertexBuffer.Draw, DynamicCapture.Material> materials =
                    value(DynamicCapture.class, "DRAWS", null);
            if (known)
                materials.put(draw, new DynamicCapture.Material(0, 1, false, true));
            BufferBuilder consumer = (BufferBuilder)staged.getVertexBuilder(draw);
            Class<?> groupType =
                    Class.forName(RenderTypeFeatureRenderer.class.getName() + "$Group");
            var ctor = groupType.getDeclaredConstructor(StagedVertexBuffer.class, boolean.class);
            ctor.setAccessible(true);
            Object group = ctor.newInstance(staged, true);
            set(groupType, "lastRenderType", group, TYPE);
            set(groupType, "lastDraw", group, draw);
            set(RenderTypeFeatureRenderer.class, "currentGroup", RENDERER, group);
            var pose = new PoseStack();
            pose.translate(10 + offset, 24, 36);
            pose.scale(-2, 3, .5f);
            var prepare = ItemFeatureRenderer.class.getDeclaredMethod(
                    "prepareMainSubmit", ItemFeatureRenderer.Submit.class);
            prepare.setAccessible(true);
            for (int i = 0; i < sources; ++i) {
                if (i == 1 && between != null)
                    between.run();
                var previous =
                        ModelCapture.beginSource(i == 0 ? FIRST : SECOND, 10 + i, 20, 30,
                                                 new Matrix4f().translation(10, 20, 30), false);
                var submit = new ItemFeatureRenderer.Submit(pose.last().copy(),
                                                            ItemDisplayContext.FIXED, 0xf000f0, 0,
                                                            outline, new int[] {tint}, quads, foil);
                ModelCapture.endSource(previous);
                check(((ModelSubmission)(Object)submit).primept$submission() != null,
                      "Constructor source identity");
                prepare.invoke(RENDERER, submit);
            }
            emitted = ((BufferBuilderAccessor)consumer).primept$vertices();
            var finish = StagedVertexBuffer.class.getDeclaredMethod("finishLastVertexBuilder");
            finish.setAccessible(true);
            finish.invoke(staged);
            check(DynamicCapture.healthy(), "Item capture healthy");
        } finally {
            DynamicCapture.end();
        }
    }
    private static void equivalent(java.nio.ByteBuffer wire, int instanceOffset) {
        var raw = java.nio.ByteBuffer.wrap(baseline).order(ByteOrder.LITTLE_ENDIAN);
        int stride = raw.getInt(80), pos = raw.getInt(84), color = raw.getInt(88),
            uv = raw.getInt(92);
        for (int vertex = 0; vertex < 8; ++vertex) {
            int p = 104 + vertex * 24, r = 96 + vertex * stride;
            float x = wire.getFloat(p), y = wire.getFloat(p + 4), z = wire.getFloat(p + 8);
            for (int row = 0; row < 3; ++row) {
                int matrix = instanceOffset + 48 + row * 16;
                double actual = wire.getDouble(instanceOffset + 24 + row * 8) +
                                wire.getFloat(matrix) * x + wire.getFloat(matrix + 4) * y +
                                wire.getFloat(matrix + 8) * z + wire.getFloat(matrix + 12);
                check(actual == raw.getFloat(r + pos + row * 4),
                      "Actual baseline vertex/affine equivalence");
            }
            check(raw.getInt(r + color) == wire.getInt(instanceOffset + 104),
                  "Actual encoded baseline tint equivalence");
            check(raw.getInt(r + uv) == wire.getInt(p + 16) &&
                          raw.getInt(r + uv + 4) == wire.getInt(p + 20),
                  "Actual UV source equivalence");
        }
    }
    private static void cornerColors() throws Exception {
        DynamicCapture.close();
        exclusive = true;
        DynamicCapture.begin(CAMERA);
        try (var staged = new StagedVertexBuffer(() -> "Item corner colors", 1024)) {
            var draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS);
            Map<StagedVertexBuffer.Draw, DynamicCapture.Material> materials =
                    value(DynamicCapture.class, "DRAWS", null);
            materials.put(draw, new DynamicCapture.Material(0, 1, false, true));
            var consumer = staged.getVertexBuilder(draw);
            var pose = new PoseStack();
            var previous = ModelCapture.beginSource(FIRST, 0, 0, 0, new Matrix4f(), false);
            var q = quad(new Vector3f());
            var submit = new ItemFeatureRenderer.Submit(pose.last(), ItemDisplayContext.FIXED, 0, 0,
                                                        0, new int[0], List.of(q), FoilType.NONE);
            ModelCapture.endSource(previous);
            var before = ItemCapture.enter(submit, true);
            var colors = new com.mojang.blaze3d.vertex.QuadInstance();
            int[] authored = {0xff123456, 0x80432165, 0x10102030, 0xfedcba98};
            for (int i = 0; i < 4; ++i)
                colors.setColor(i, authored[i]);
            check(ItemCapture.quad(consumer, pose.last(), q, colors),
                  "Per-corner source is routed");
            ItemCapture.leave(before, true);
            DynamicCapture.end();
            var context = context();
            var wire = context.sealDelta().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            check(context.stats().prototypeUpserts() == 1 && context.stats().instanceUpserts() == 1,
                  "Per-corner source retains one prototype and instance");
            for (int i = 0; i < 4; ++i) {
                int offset = 104 + i * 24 + 12, color = authored[i];
                check(Byte.toUnsignedInt(wire.get(offset)) == ((color >>> 16) & 255) &&
                              Byte.toUnsignedInt(wire.get(offset + 1)) == ((color >>> 8) & 255) &&
                              Byte.toUnsignedInt(wire.get(offset + 2)) == (color & 255) &&
                              Byte.toUnsignedInt(wire.get(offset + 3)) == (color >>> 24),
                      "Authored RGBA is preserved at corner " + i);
            }
            check(wire.getInt(104 + 4 * 24 + 104) == -1, "No duplicate instance tint");
            check(((BufferBuilderAccessor)consumer).primept$vertices() == 0,
                  "Per-corner colors do not restore downstream expansion");
            context.acknowledge();
        } finally {
            DynamicCapture.end();
        }
    }
    private static void unknownConsumer() throws Exception {
        exclusive = true;
        DynamicCapture.begin(CAMERA);
        try {
            var pose = new PoseStack();
            var previous = ModelCapture.beginSource(FIRST, 0, 0, 0, new Matrix4f(), false);
            var q = quad(new Vector3f());
            var submit = new ItemFeatureRenderer.Submit(pose.last(), ItemDisplayContext.FIXED, 0, 0,
                                                        0, new int[0], List.of(q), FoilType.NONE);
            ModelCapture.endSource(previous);
            var before = ItemCapture.enter(submit, true);
            int[] calls = {0};
            var unknown =
                    (com.mojang.blaze3d.vertex.VertexConsumer)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    ItemCpuSmoke.class.getClassLoader(),
                                    new Class<?>[] {com.mojang.blaze3d.vertex.VertexConsumer.class},
                                    (proxy, method, args) -> {
                                        ++calls[0];
                                        throw new AssertionError(
                                                "Must not inspect unknown consumer");
                                    });
            check(ItemCapture.quad(unknown, pose.last(), q,
                                   new com.mojang.blaze3d.vertex.QuadInstance()) &&
                          calls[0] == 0,
                  "Unknown downstream consumer is untouched, rejected explicitly, and never called");
            ItemCapture.leave(before, true);
        } finally {
            DynamicCapture.end();
        }
    }
    private static InstanceCapture context() throws Exception {
        return value(ModelCapture.class, "instances", null);
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
    private static void check(boolean pass, String message) {
        if (!pass)
            throw new AssertionError(message);
    }
    private static final class Owner implements ModelSourceOwner {
        ModelCapture.Source source;
        public ModelCapture.Source primept$modelSource() {
            return source;
        }
        public void primept$modelSource(ModelCapture.Source value) {
            source = value;
        }
    }
}
