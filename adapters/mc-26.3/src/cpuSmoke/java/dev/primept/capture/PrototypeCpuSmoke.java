package dev.primept.capture;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.primept.mixin.BufferBuilderAccessor;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Separate test mod, runs before Minecraft main and never initializes a window or GPU. */
public final class PrototypeCpuSmoke implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            String samplingRegistry = System.getProperty("primept.sampling.registry", "");
            if (!samplingRegistry.isEmpty()) {
                SamplingRegistryDump.run(java.nio.file.Path.of(samplingRegistry));
                System.exit(0);
            }
            if (Boolean.getBoolean("primept.section.suite")) {
                SectionSuite.run();
                System.exit(0);
            }
            String routingCost = System.getProperty("primept.smoke.routingCost", "");
            if (!routingCost.isEmpty()) {
                TerrainRoutingCostCpuSmoke.run(routingCost);
                System.exit(0);
            }
            if (Boolean.getBoolean("primept.smoke.targetResize")) {
                TargetResizeCpuSmoke.run();
                System.exit(0);
            }
            if (Boolean.getBoolean("primept.smoke.foreignWrapper")) {
                GeometryCacheCpuSmoke.foreign();
                ItemCpuSmoke.foreign();
                SectionSourcesCpuSmoke.run();
                System.exit(0);
            }
            TargetResizeCpuSmoke.run();
            SettingsCpuSmoke.run();
            AstronomyCpuSmoke.run();
            GeometryCacheCpuSmoke.run();
            CanonicalTextureCpuSmoke.run();
            FabricMeshCpuSmoke.run();
            ItemCpuSmoke.run();
            ExclusiveTerrainCpuSmoke.run();
            dev.primept.RenderProfileCpuSmoke.run();
            ModelSpriteCpuSmoke.run();
            run();
            System.out.println(
                    "PRIME_PT_CPU_SMOKE_OK: actual transformed Cube/Draw hooks, 10000 instances, unchanged frame=0B, mutation, skipped native submit, raw fallback");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
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
    private static void run() throws Exception {
        // Loading forces each actual version's mixin targets to transform, without initializing render devices.
        for (String name :
             List.of("net.minecraft.client.renderer.entity.EntityRenderDispatcher",
                     "net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher",
                     "net.minecraft.client.renderer.feature.ModelFeatureRenderer",
                     "net.minecraft.client.renderer.SpriteCoordinateExpander"))
            Class.forName(name, false, PrototypeCpuSmoke.class.getClassLoader());
        check(ModelSourceOwner.class.isAssignableFrom(net.minecraft.world.entity.Entity.class),
              "Entity identity owner mixin");
        check(ModelSourceOwner.class.isAssignableFrom(
                      net.minecraft.world.level.block.entity.BlockEntity.class),
              "BE identity owner mixin");
        check(SourceIdentity.class.isAssignableFrom(
                      net.minecraft.client.renderer.entity.state.EntityRenderState.class),
              "state identity mixin");
        var cube = new ModelPart.Cube(0, 0, 0, 0, 0, 16, 16, 16, 0, 0, 0, false, 64, 64,
                                      EnumSet.allOf(Direction.class));
        var model = new CountingModel(cube);
        var camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        Owner[] owners = new Owner[10_000];
        for (int i = 0; i < owners.length; ++i)
            owners[i] = new Owner();
        frame(camera, owners, model);
        check(ModelCapture.stats().leaves() == owners.length,
              "All standard leaves instanced: " + ModelCapture.stats());
        check(ModelCapture.stats().geometryVerticesRead() == 24, "Only one resource mesh read");
        // Deliberately do not seal/submit the first observation; the next frame must remain legal.
        frame(camera, owners, model);
        InstanceCapture context = field(ModelCapture.class, "instances", null);
        var first = context.sealDelta();
        check(first != null &&
                      first.asByteBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(40) ==
                              owners.length,
              "Pending first definitions preserved");
        context.acknowledge();
        frame(camera, owners, model);
        check(context.sealDelta() == null, "Stable 10k objects emit zero delta bytes");
        check(ModelCapture.stats().geometryVerticesRead() == 0,
              "No stable-frame vertex value scan");
        var v = cube.polygons[0].vertices()[0];
        cube.polygons[0].vertices()[0] =
                new ModelPart.Vertex(v.x() + 2, v.y(), v.z(), v.u(), v.v());
        frame(camera, owners, model);
        var changed = context.sealDelta();
        check(changed != null, "Actual public-array mutation publishes geometry");
        context.acknowledge();
        check(ModelCapture.stats().geometryVerticesRead() == 24,
              "Exactly one mutated prototype read");
        check(model.calls == owners.length * 4, "setupAnim actual callback count");
        model.mutateAt = model.calls + owners.length / 2;
        frame(camera, owners, model);
        check(ModelCapture.stats().geometryVerticesRead() == 24,
              "Callback mutation in the middle of one frame is observed immediately");
        var split = context.sealDelta();
        check(split != null && context.stats().activeInstances() == owners.length,
              "Old and new prototype instances coexist");
        check(context.stats().prototypeRemoves() == 0,
              "Earlier same-frame instances still retain the old prototype");
        context.acknowledge();
        unknownLeafFallback(camera);
        DynamicCapture.close();
    }
    @SuppressWarnings("unchecked")
    private static void frame(CameraRenderState camera, Owner[] owners, CountingModel model)
            throws Exception {
        long started = System.nanoTime();
        DynamicCapture.begin(camera);
        try (var staged = new StagedVertexBuffer(() -> "CPU fixture", 1 << 20)) {
            var draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS);
            Map<StagedVertexBuffer.Draw, DynamicCapture.Material> materials =
                    field(DynamicCapture.class, "DRAWS", null);
            materials.put(draw, new DynamicCapture.Material(0, 0, false, true));
            var consumer = staged.getVertexBuilder(draw);
            var pose = new PoseStack();
            var base = new Matrix4f();
            for (int i = 0; i < owners.length; ++i) {
                var oldSource = ModelCapture.beginSource(owners[i], i, 0, 0, base, true);
                var submit = new ModelFeatureRenderer.Submit<>(null, pose.last().copy(), model,
                                                               null, 0, 0, -1, null, null);
                ModelCapture.endSource(oldSource);
                var oldModel = ModelCapture.beginModel(submit);
                model.setupAnim(null);
                model.renderToBuffer(pose, consumer, 0, 0, -1);
                ModelCapture.endModel(oldModel);
            }
            // Unrecognized non-model output remains in the actual raw packet.
            for (int i = 0; i < 4; ++i)
                consumer.addVertex(i, 0, 0)
                        .setColor(-1)
                        .setUv(0, 0)
                        .setOverlay(0)
                        .setLight(0)
                        .setNormal(0, 1, 0);
            check(((BufferBuilderAccessor)consumer).primept$vertices() == owners.length * 24 + 4,
                  "Original raster output still emitted exactly once");
            var finish = StagedVertexBuffer.class.getDeclaredMethod("finishLastVertexBuilder");
            finish.setAccessible(true);
            finish.invoke(staged);
            check(DynamicCapture.healthy(), "Capture stayed healthy");
            check(DynamicCapture.stats().vertices() == 4,
                  "Only raw fallback remains, no double geometry");
            check(DynamicCapture.stats().modelRawVertices() == 4 &&
                          DynamicCapture.stats().particleRawVertices() == 0,
                  "Raw counts exclude standard instances");
            for (Owner owner : owners)
                for (var submission : owner.source.submits)
                    for (var leaf : submission.leaves)
                        check(leaf.buffer == null,
                              "Persistent leaves release temporary Minecraft buffers");
        } finally {
            DynamicCapture.end();
        }
        System.out.println("CPU fixture objects=" + owners.length +
                           " originalVertices=" + (owners.length * 24 + 4) +
                           " rawVertices=" + DynamicCapture.stats().vertices() +
                           " instances=" + ModelCapture.stats().leaves() +
                           " refs=" + ModelCapture.stats().referenceChecks() +
                           " geometryReads=" + ModelCapture.stats().geometryVerticesRead() +
                           " elapsedMs=" + ((System.nanoTime() - started) / 1_000_000.0));
    }
    private static void unknownLeafFallback(CameraRenderState camera) throws Exception {
        ItemCpuSmoke.exclusive = true;
        DynamicCapture.begin(camera);
        try (var staged = new StagedVertexBuffer(() -> "unknown leaf", 1024)) {
            var draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS);
            Map<StagedVertexBuffer.Draw, DynamicCapture.Material> materials =
                    field(DynamicCapture.class, "DRAWS", null);
            materials.put(draw, new DynamicCapture.Material(0, 0, false, true));
            var consumer = staged.getVertexBuilder(draw);
            var pose = new PoseStack();
            var custom = new ModelPart.Cube(0, 0, 0, 0, 0, 16, 16, 16, 0, 0, 0, false, 64, 64,
                                            EnumSet.allOf(Direction.class)) {
                @Override
                public void compile(PoseStack.Pose pose,
                                    com.mojang.blaze3d.vertex.VertexConsumer consumer, int light,
                                    int overlay, int color) {
                    throw new AssertionError("Downstream override must be cut off");
                }
            };
            var counting = new CountingModel(custom);
            var oldSource = ModelCapture.beginSource(new Owner(), 0, 0, 0, new Matrix4f(), true);
            var submit = new ModelFeatureRenderer.Submit<>(null, pose.last().copy(), counting, null,
                                                           0, 0, -1, null, null);
            ModelCapture.endSource(oldSource);
            var oldModel = ModelCapture.beginModel(submit);
            counting.setupAnim(null);
            counting.renderToBuffer(pose, consumer, 0, 0, -1);
            ModelCapture.endModel(oldModel);
            var finish = StagedVertexBuffer.class.getDeclaredMethod("finishLastVertexBuilder");
            finish.setAccessible(true);
            finish.invoke(staged);
            check(ModelCapture.stats().leaves() == 1 && ModelCapture.stats().fallbackLeaves() == 0,
                  "Custom local model geometry is routed");
            check(DynamicCapture.stats().vertices() == 0,
                  "No downstream expansion or duplicate raw output");
            check(counting.calls == 1, "Unknown callback is not replayed");
        } finally {
            ItemCpuSmoke.exclusive = false;
            DynamicCapture.end();
        }
    }
    private static final class Owner implements ModelSourceOwner {
        ModelCapture.Source source;
        public ModelCapture.Source primept$modelSource() {
            return source;
        }
        public void primept$modelSource(ModelCapture.Source source) {
            this.source = source;
        }
        @Override
        public boolean equals(Object ignored) {
            return true;
        } // Identity must never call this.
        @Override
        public int hashCode() {
            return 0;
        }
    }
    private static final class CountingModel extends Model<Object> {
        int calls, mutateAt = -1;
        final ModelPart.Cube cube;
        CountingModel(ModelPart.Cube cube) {
            super(new ModelPart(List.of(cube), Map.of()), ignored -> null);
            this.cube = cube;
        }
        @Override
        public void setupAnim(Object ignored) {
            if (++calls == mutateAt) {
                var old = cube.polygons[0].vertices()[0];
                cube.polygons[0].vertices()[0] =
                        new ModelPart.Vertex(old.x() + 1, old.y(), old.z(), old.u(), old.v());
            }
        }
    }
}
