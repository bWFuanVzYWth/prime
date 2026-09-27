package dev.primept.capture;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.model.geom.builders.UVPair;
import org.joml.Matrix4fc;

/** Observes the one real item submit; mutable quad values are checked, never inferred from identity. */
public final class ItemCapture {
    private static Context context;
    private static Scope current;
    private static long submits, groups, checkedVertices, skippedVertices, fallbackQuads,
            createdVertices, sharedHits;
    public record Stats(long submits, long groups, long checkedVertices, long skippedVertices,
                        long fallbackQuads, long createdVertices, long sharedHits) {}
    public static Stats stats() {
        return new Stats(submits, groups, checkedVertices, skippedVertices, fallbackQuads,
                         createdVertices, sharedHits);
    }
    private ItemCapture() {}
    static void begin(InstanceCapture owner) {
        if (context == null || context.owner != owner)
            context = new Context(owner);
        ++context.frame;
        current = null;
        submits = groups = checkedVertices = skippedVertices = fallbackQuads = createdVertices =
                sharedHits = 0;
    }
    static void close() {
        if (context != null) {
            // The entire InstanceCapture closes next; no per-handle mutation is legal after a failed sealed submit.
            for (Scope scope : context.previous)
                for (Group group : scope.groups) {
                    group.geometry = null;
                    group.changed = null;
                }
            for (Scope scope : context.visible)
                for (Group group : scope.groups) {
                    group.geometry = null;
                    group.changed = null;
                }
        }
        context = null;
        current = null;
    }
    static void end() {
        if (context == null)
            return;
        for (Scope scope : context.previous)
            if (scope.seen != context.frame)
                for (Group group : scope.groups)
                    group.clear();
        var spare = context.previous;
        context.previous = context.visible;
        context.visible = spare;
        context.visible.clear();
        current = null;
    }

    public static Scope enter(ItemFeatureRenderer.Submit submit, boolean exclusive) {
        Scope previous = current;
        current = null;
        if (!exclusive || !DynamicCapture.active() || context == null ||
            submit.outlineColor() != 0 || submit.foilType() != ItemStackRenderState.FoilType.NONE)
            return previous;
        var source = ((ModelSubmission)(Object)submit).primept$submission();
        if (source == null || source.itemFrame == context.frame)
            return previous;
        if (source.itemScope == null)
            source.itemScope = new Scope(source);
        current = source.itemScope;
        if (current.seen != context.frame) {
            current.seen = context.frame;
            context.visible.add(current);
        }
        for (Group group : current.groups)
            group.used = false;
        current.accepted = 0;
        return previous;
    }
    public static void leave(Scope previous, boolean completed) {
        Scope active = current;
        current = previous;
        if (active == null || !completed || !DynamicCapture.active())
            return;
        try {
            for (Group group : active.groups)
                if (group.used) {
                    group.finish();
                    var source = active.source;
                    context.owner.observe(group.instance, group.geometry.prototype, source.x,
                                          source.y, source.z, group.affine, group.texture,
                                          group.flags, group.tint, group.uv);
                    ++groups;
                } else
                    group.clear();
            if (active.accepted != 0) {
                ++submits;
                active.source.itemFrame = context.frame;
            }
        } catch (RuntimeException failure) {
            DynamicCapture.fail(failure);
        }
    }

    /** Called at the actual putBakedQuad invocation, after material/tint/getVertexBuilder callbacks. */
    public static boolean quad(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad,
                               QuadInstance colors) {
        if (current == null || !DynamicCapture.active()) {
            if (DynamicCapture.active())
                ++fallbackQuads;
            return false;
        }
        if (consumer.getClass() != BufferBuilder.class) {
            ++fallbackQuads;
            DynamicCapture.fail(new IllegalArgumentException("Unsupported routed item consumer"));
            return true;
        }
        var material = DynamicCapture.material((BufferBuilder)consumer);
        Matrix4fc matrix = pose.pose();
        if (material == null || material.particle()) {
            ++fallbackQuads;
            return true;
        }
        int tint = colors.getColor(0);
        int c1 = colors.getColor(1), c2 = colors.getColor(2), c3 = colors.getColor(3);
        boolean uniform = tint == c1 && tint == c2 && tint == c3;
        int c0 = tint;
        if (!uniform)
            tint = -1;
        try {
            Group group = current.group(material, tint, matrix);
            for (int i = 0; i < 4; ++i) {
                var position = quad.position(i);
                long uv = quad.packedUV(i);
                group.value(Float.floatToRawIntBits(position.x()));
                group.value(Float.floatToRawIntBits(position.y()));
                group.value(Float.floatToRawIntBits(position.z()));
                int argb = uniform ? -1 : switch (i) {
                    case 0 -> c0;
                    case 1 -> c1;
                    case 2 -> c2;
                    default -> c3;
                };
                group.value((argb & 0xff00ff00) | ((argb >>> 16) & 255) | ((argb & 255) << 16));
                group.value(Float.floatToRawIntBits(UVPair.unpackU(uv)));
                group.value(Float.floatToRawIntBits(UVPair.unpackV(uv)));
            }
            checkedVertices += 4;
            skippedVertices += 4;
            current.accepted += 4;
            return true;
        } catch (RuntimeException failure) {
            DynamicCapture.fail(failure);
            return true;
        }
    }

    public static final class Scope {
        final ModelCapture.Submission source;
        final ArrayList<Group> groups = new ArrayList<>();
        int accepted;
        long seen;
        Scope(ModelCapture.Submission source) {
            this.source = source;
        }
        Group group(DynamicCapture.Material material, int tint, Matrix4fc matrix) {
            Group unused = null;
            for (Group group : groups) {
                if (!group.used) {
                    if (unused == null)
                        unused = group;
                } else if (group.texture == material.texture() && group.flags == material.flags() &&
                           group.tint == tint && group.samePose(matrix, source))
                    return group;
            }
            if (unused == null) {
                unused = new Group(context.owner.instance());
                groups.add(unused);
            }
            unused.start(material, tint, matrix, source);
            return unused;
        }
    }
    private static final class Group {
        final InstanceCapture.Instance instance;
        final float[] affine = new float[12], uv = {1, 1, 0, 0};
        Geometry geometry;
        ByteBuffer changed;
        int cursor, texture, flags, tint;
        boolean used, dirty;
        Group(InstanceCapture.Instance instance) {
            this.instance = instance;
        }
        void clear() {
            replace(null);
            changed = null;
        }
        void replace(Geometry replacement) {
            if (geometry == replacement)
                return;
            if (replacement != null)
                ++replacement.references;
            context.release(geometry);
            geometry = replacement;
        }
        void start(DynamicCapture.Material material, int tint, Matrix4fc m,
                   ModelCapture.Submission source) {
            used = true;
            dirty = false;
            cursor = 0;
            texture = material.texture();
            flags = material.flags();
            this.tint = tint;
            affine[0] = m.m00();
            affine[1] = m.m10();
            affine[2] = m.m20();
            affine[3] = m.m30() - source.bx;
            affine[4] = m.m01();
            affine[5] = m.m11();
            affine[6] = m.m21();
            affine[7] = m.m31() - source.by;
            affine[8] = m.m02();
            affine[9] = m.m12();
            affine[10] = m.m22();
            affine[11] = m.m32() - source.bz;
        }
        boolean samePose(Matrix4fc m, ModelCapture.Submission s) {
            return affine[0] == m.m00() && affine[1] == m.m10() && affine[2] == m.m20() &&
                    affine[3] == m.m30() - s.bx && affine[4] == m.m01() && affine[5] == m.m11() &&
                    affine[6] == m.m21() && affine[7] == m.m31() - s.by && affine[8] == m.m02() &&
                    affine[9] == m.m12() && affine[10] == m.m22() && affine[11] == m.m32() - s.bz;
        }
        void value(int value) {
            if (!dirty && geometry != null && cursor < geometry.bytes.remaining() &&
                geometry.bytes.getInt(cursor) == value) {
                cursor += 4;
                return;
            }
            if (!dirty) {
                reserve(Math.max(cursor + 4, geometry == null ? 576 : geometry.bytes.remaining()));
                changed.clear();
                if (cursor != 0)
                    changed.put(geometry.bytes.duplicate().limit(cursor));
                dirty = true;
            }
            reserve(cursor + 4);
            changed.putInt(value);
            cursor += 4;
        }
        void reserve(int bytes) {
            if (changed != null && changed.capacity() >= bytes)
                return;
            int capacity = Math.max(
                    576, changed == null
                                 ? bytes
                                 : Math.max(bytes, Math.multiplyExact(changed.capacity(), 2)));
            ByteBuffer replacement = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
            if (dirty && changed != null)
                replacement.put(changed.flip());
            changed = replacement;
        }
        void finish() {
            if (!dirty && geometry != null && cursor == geometry.bytes.remaining())
                return;
            ByteBuffer bytes =
                    dirty ? changed.duplicate().flip().order(ByteOrder.LITTLE_ENDIAN)
                          : geometry.bytes.duplicate().limit(cursor).order(ByteOrder.LITTLE_ENDIAN);
            replace(context.geometry(bytes));
        }
    }
    private static final class Geometry {
        final ByteBuffer bytes;
        final InstanceCapture.Prototype prototype;
        final int hash;
        int references;
        Geometry(ByteBuffer bytes, InstanceCapture.Prototype prototype, int hash) {
            this.bytes = bytes;
            this.prototype = prototype;
            this.hash = hash;
        }
    }
    private static final class Context {
        final InstanceCapture owner;
        final HashMap<Integer, ArrayList<Geometry>> pool = new HashMap<>();
        ArrayList<Scope> previous = new ArrayList<>(), visible = new ArrayList<>();
        long frame;
        Context(InstanceCapture owner) {
            this.owner = owner;
        }
        Geometry geometry(ByteBuffer source) {
            int hash = 1;
            for (int i = 0; i < source.remaining(); i += 4)
                hash = 31 * hash + source.getInt(i);
            var bucket = pool.computeIfAbsent(hash, ignored -> new ArrayList<>());
            for (Geometry geometry : bucket) {
                if (geometry.bytes.mismatch(source) == -1) {
                    ++sharedHits;
                    return geometry;
                }
            }
            ByteBuffer copy =
                    ByteBuffer.allocate(source.remaining()).order(ByteOrder.LITTLE_ENDIAN);
            copy.put(source).flip();
            var prototype = owner.prototype(4, copy.remaining() / 24, 24, 0, 12, 16, copy);
            Geometry geometry = new Geometry(copy, prototype, hash);
            bucket.add(geometry);
            createdVertices += copy.remaining() / 24;
            return geometry;
        }
        void release(Geometry geometry) {
            if (geometry == null || --geometry.references != 0)
                return;
            var bucket = pool.get(geometry.hash);
            bucket.remove(geometry);
            if (bucket.isEmpty())
                pool.remove(geometry.hash);
            owner.release(geometry.prototype);
        }
    }
}
