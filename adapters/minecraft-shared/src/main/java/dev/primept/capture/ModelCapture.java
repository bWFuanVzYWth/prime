package dev.primept.capture;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.primept.NativeBridge;
import dev.primept.mixin.BufferBuilderAccessor;
import dev.primept.mixin.SpriteConsumerAccessor;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Matrix4fc;

/** Separate resource, world identity and actual-frame observation contexts; never replays model callbacks. */
public final class ModelCapture {
    private static final boolean PROFILE =
            Boolean.getBoolean("primept.profile") && Boolean.getBoolean("primept.profile.leaves");
    public static boolean leafTimingEnabled() {
        return PROFILE;
    }
    private static InstanceCapture instances;
    private static ModelGeometryContext geometry;
    private static long epoch, frameNumber;
    private static Source source;
    private static Submission model;
    private static long leaves, fallbackLeaves, referenceNanos, skippedVertices;
    private static int deltaBytes, blockEntitySources, entitySources, modelSubmissions;
    private static long checkStart, readStart;
    private static boolean ended;
    public record
            Stats(long leaves, long fallbackLeaves, long referenceChecks, long geometryVerticesRead,
                  long referenceNanos, int deltaBytes, int blockEntities, int entities,
                  int submissions, InstanceCapture.Stats delta, long skippedVertices) {}
    public static Stats stats() {
        return new Stats(leaves, fallbackLeaves,
                         geometry == null ? 0 : geometry.referenceChecks() - checkStart,
                         geometry == null ? 0 : geometry.geometryReads() - readStart,
                         referenceNanos, deltaBytes, blockEntitySources, entitySources,
                         modelSubmissions, instances == null ? null : instances.stats(),
                         skippedVertices);
    }
    private ModelCapture() {}

    public static void begin(long nextEpoch) {
        if (instances == null || nextEpoch != epoch) {
            close();
            epoch = nextEpoch;
            instances = new InstanceCapture(epoch);
            geometry = new ModelGeometryContext(instances);
        }
        ++frameNumber;
        leaves = fallbackLeaves = referenceNanos = skippedVertices = 0;
        deltaBytes = 0;
        source = null;
        model = null;
        ended = false;
        instances.beginFrame();
        geometry.collectGarbage();
        FabricMeshCapture.begin(instances);
        ItemCapture.begin(instances);
        blockEntitySources = entitySources = modelSubmissions = 0;
        checkStart = geometry.referenceChecks();
        readStart = geometry.geometryReads();
    }
    public static void end() {
        source = null;
        model = null;
        if (instances != null && !ended) {
            ItemCapture.end();
            instances.endFrame();
            ended = true;
        }
    }
    public static void submit(NativeBridge bridge) {
        if (instances == null)
            return;
        if (!ended) {
            ItemCapture.end();
            instances.endFrame();
            ended = true;
        }
        MemorySegment packet = instances.sealDelta();
        if (packet != null) {
            deltaBytes = Math.toIntExact(packet.byteSize());
            bridge.submit(packet);
            instances.acknowledge();
        }
    }
    public static Source beginSource(Object identity, double x, double y, double z, Matrix4fc base,
                                     boolean blockEntity) {
        Source previous = source;
        source = null;
        if (!DynamicCapture.active() || !(identity instanceof ModelSourceOwner owner) ||
            instances == null || base.m00() != 1 || base.m11() != 1 || base.m22() != 1 ||
            base.m01() != 0 || base.m02() != 0 || base.m10() != 0 || base.m12() != 0 ||
            base.m20() != 0 || base.m21() != 0)
            return previous;
        if (blockEntity)
            ++blockEntitySources;
        else
            ++entitySources;
        source = owner.primept$modelSource();
        if (source == null || source.owner != instances) {
            source = new Source(instances);
            owner.primept$modelSource(source);
        }
        if (source.frame != frameNumber) {
            source.frame = frameNumber;
            source.cursor = 0;
        }
        source.x = x;
        source.y = y;
        source.z = z;
        source.bx = base.m30();
        source.by = base.m31();
        source.bz = base.m32();
        return previous;
    }
    public static void endSource(Source previous) {
        source = previous;
    }
    public static Submission tagSubmission() {
        if (source == null || !DynamicCapture.active())
            return null;
        ++modelSubmissions;
        int index = source.cursor++;
        if (index == source.submits.size())
            source.submits.add(new Submission());
        Submission submission = source.submits.get(index);
        submission.x = source.x;
        submission.y = source.y;
        submission.z = source.z;
        submission.bx = source.bx;
        submission.by = source.by;
        submission.bz = source.bz;
        return submission;
    }
    public static Submission beginModel(ModelFeatureRenderer.Submit<?> submit) {
        Submission previous = model;
        model = (Object)submit instanceof ModelSubmission attached ? attached.primept$submission()
                                                                   : null;
        if (model != null)
            model.cursor = 0;
        return previous;
    }
    public static void endModel(Submission previous) {
        model = previous;
    }

    public static boolean routedModel() {
        return model != null && DynamicCapture.active();
    }

    public static Leaf beforeCube(ModelPart.Cube cube, PoseStack.Pose pose, VertexConsumer consumer,
                                  int color) {
        if (model == null || !DynamicCapture.active())
            return null;
        int slot = model.cursor++;
        long start = PROFILE ? System.nanoTime() : 0;
        try {
            TextureAtlasSprite sprite = null;
            if (consumer.getClass() == SpriteCoordinateExpander.class) {
                var wrapper = (SpriteConsumerAccessor)consumer;
                Object mapping = wrapper.primept$mapping();
                if (!(mapping instanceof TextureAtlasSprite actualSprite)) {
                    ++fallbackLeaves;
                    throw new IllegalArgumentException("Unsupported routed model UV mapping");
                }
                sprite = actualSprite;
                if (sprite.getClass() != TextureAtlasSprite.class) {
                    ++fallbackLeaves;
                    throw new IllegalArgumentException("Unsupported routed model UV mapping");
                }
                consumer = wrapper.primept$delegate();
            }
            if (consumer.getClass() != BufferBuilder.class) {
                ++fallbackLeaves;
                DynamicCapture.fail(
                        new IllegalArgumentException("Unsupported routed model consumer"));
                return null;
            }
            BufferBuilder buffer = (BufferBuilder)consumer;
            var material = DynamicCapture.material(buffer);
            if (material == null || material.particle()) {
                ++fallbackLeaves;
                return null;
            }
            var local = geometry.observe(cube);
            if (local == null) {
                if (cube.polygons.length != 0) {
                    ++fallbackLeaves;
                    throw new IllegalArgumentException("Unsupported routed model polygon layout");
                }
                return null;
            }
            if (local.prototype == null)
                local.prototype =
                        instances.prototype(4, local.vertexCount(), 24, 0, 12, 16, local.bytes());
            while (slot >= model.leaves.size())
                model.leaves.add(new Leaf(instances.instance()));
            Leaf leaf = model.leaves.get(slot);
            leaf.source = model;
            leaf.geometry = local;
            leaf.start = ((BufferBuilderAccessor)buffer).primept$vertices();
            leaf.texture = material.texture();
            leaf.flags = material.flags();
            leaf.color = color;
            var matrix = pose.pose();
            float[] m = leaf.affine;
            m[0] = matrix.m00();
            m[1] = matrix.m10();
            m[2] = matrix.m20();
            m[3] = matrix.m30() - model.bx;
            m[4] = matrix.m01();
            m[5] = matrix.m11();
            m[6] = matrix.m21();
            m[7] = matrix.m31() - model.by;
            m[8] = matrix.m02();
            m[9] = matrix.m12();
            m[10] = matrix.m22();
            m[11] = matrix.m32() - model.bz;
            // Native validates affine finiteness and invertibility at the input boundary.
            leaf.uv[0] = sprite == null ? 1 : sprite.getU1() - sprite.getU0();
            leaf.uv[1] = sprite == null ? 1 : sprite.getV1() - sprite.getV0();
            leaf.uv[2] = sprite == null ? 0 : sprite.getU0();
            leaf.uv[3] = sprite == null ? 0 : sprite.getV0();
            leaf.buffer = buffer;
            return leaf;
        } catch (RuntimeException exception) {
            DynamicCapture.fail(exception);
            return null;
        } finally {
            if (PROFILE)
                referenceNanos += System.nanoTime() - start;
        }
    }
    public static void afterCube(Leaf leaf, boolean completed) {
        if (leaf == null)
            return;
        long start = PROFILE ? System.nanoTime() : 0;
        try {
            if (!completed || !DynamicCapture.active())
                return;
            int end = ((BufferBuilderAccessor)leaf.buffer).primept$vertices();
            if (end - leaf.start != leaf.geometry.vertexCount()) {
                ++fallbackLeaves;
                return;
            }
            Submission s = leaf.source;
            instances.observe(leaf.instance, leaf.geometry.prototype, s.x, s.y, s.z, leaf.affine,
                              leaf.texture, leaf.flags, leaf.color, leaf.uv);
            DynamicCapture.exclude(leaf.buffer, leaf.start, end);
            ++leaves;
        } catch (RuntimeException exception) {
            DynamicCapture.fail(exception);
        } finally {
            leaf.buffer =
                    null; // A persistent source never owns Minecraft's temporary vertex builder.
            if (PROFILE)
                referenceNanos += System.nanoTime() - start;
        }
    }
    /** The caller owns this frame exclusively; this leaf is the validated pure vertex-expansion path. */
    public static boolean skipCube(Leaf leaf, boolean exclusive) {
        if (leaf == null || !exclusive || !DynamicCapture.active())
            return false;
        long start = PROFILE ? System.nanoTime() : 0;
        try {
            Submission s = leaf.source;
            instances.observe(leaf.instance, leaf.geometry.prototype, s.x, s.y, s.z, leaf.affine,
                              leaf.texture, leaf.flags, leaf.color, leaf.uv);
            ++leaves;
            skippedVertices += leaf.geometry.vertexCount();
            leaf.buffer = null;
            return true;
        } catch (RuntimeException exception) {
            DynamicCapture.fail(exception);
            return false;
        } finally {
            if (PROFILE)
                referenceNanos += System.nanoTime() - start;
        }
    }
    public static void close() {
        FabricMeshCapture.close();
        ItemCapture.close();
        if (instances != null)
            instances.close();
        instances = null;
        geometry = null;
        source = null;
        model = null;
    }
    public static final class Source {
        final InstanceCapture owner;
        Source(InstanceCapture owner) {
            this.owner = owner;
        }
        final ArrayList<Submission> submits = new ArrayList<>();
        long frame;
        int cursor;
        double x, y, z;
        float bx, by, bz;
    }
    public static final class Submission {
        final ArrayList<Leaf> leaves = new ArrayList<>();
        final ArrayList<FabricMeshCapture.Instance> meshes = new ArrayList<>();
        long meshFrame, itemFrame;
        ItemCapture.Scope itemScope;
        int cursor;
        double x, y, z;
        float bx, by, bz;
    }
    public static final class Leaf {
        final InstanceCapture.Instance instance;
        final float[] affine = new float[12], uv = new float[4];
        Submission source;
        ModelGeometryContext.Geometry geometry;
        BufferBuilder buffer;
        int start, texture, flags, color;
        Leaf(InstanceCapture.Instance instance) {
            this.instance = instance;
        }
    }
}
