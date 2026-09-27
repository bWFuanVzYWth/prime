package dev.primept.capture;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.render.submit.ExtendedBlockModelSubmit;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.util.ARGB;

/** Immutable FRAPI resource geometry, actual submit state, and native residency have separate owners. */
public final class FabricMeshCapture {
    private static Context context;
    private static FabricBufferAccess binding;
    private static long submits, groups, geometryVertices, skippedVertices, fallbacks;
    private FabricMeshCapture() {}
    public record Stats(long submits, long groups, long geometryVertices, long skippedVertices,
                        long fallbacks) {}
    public static Stats stats() {
        return new Stats(submits, groups, geometryVertices, skippedVertices, fallbacks);
    }
    public static void binding(FabricBufferAccess value) {
        binding = value;
    }
    static void begin(InstanceCapture owner) {
        if (context == null || context.owner != owner)
            context = new Context(owner);
        ++context.frame;
        context.collect();
        submits = groups = geometryVertices = skippedVertices = fallbacks = 0;
        binding = null;
    }
    static void close() {
        context = null;
        binding = null;
    }

    public static boolean output(ExtendedBlockModelSubmit submit, Mesh mesh, boolean exclusive) {
        if (!exclusive || !DynamicCapture.active() || context == null || binding == null)
            return false;
        if (submit.sheetedDecalPose() != null) {
            ++fallbacks;
            return false;
        }
        var source = ((ModelSubmission)(Object)submit).primept$submission();
        if (source == null || source.meshFrame == context.frame) {
            ++fallbacks;
            return false;
        }
        Geometry geometry;
        try {
            geometry = context.geometry(mesh);
        } catch (RuntimeException failure) {
            DynamicCapture.fail(failure);
            return true;
        }
        if (geometry.parts.isEmpty())
            return true;
        var matrix = submit.pose().pose();
        try {
            while (source.meshes.size() < geometry.parts.size())
                source.meshes.add(new Instance(context.owner.instance()));
            for (int i = 0; i < geometry.parts.size(); ++i) {
                Part part = geometry.parts.get(i);
                // Invoke the real material callback in source run order. BufferCache itself
                // preserves adjacent layer reuse; no model callback is replayed.
                VertexConsumer consumer = binding.primept$buffer(part.layer);
                if (consumer == null)
                    continue;
                if (!(consumer instanceof BufferBuilder builder))
                    throw new IllegalArgumentException("Unsupported routed mesh consumer");
                var material = DynamicCapture.material(builder);
                if (material == null || material.particle())
                    throw new IllegalArgumentException("Missing routed mesh material");
                if (part.prototype == null)
                    part.prototype = context.owner.prototype(4, part.vertices.remaining() / 24, 24,
                                                             0, 12, 16, part.vertices);
                Instance instance = source.meshes.get(i);
                float[] m = instance.affine;
                m[0] = matrix.m00();
                m[1] = matrix.m10();
                m[2] = matrix.m20();
                m[3] = matrix.m30() - source.bx;
                m[4] = matrix.m01();
                m[5] = matrix.m11();
                m[6] = matrix.m21();
                m[7] = matrix.m31() - source.by;
                m[8] = matrix.m02();
                m[9] = matrix.m12();
                m[10] = matrix.m22();
                m[11] = matrix.m32() - source.bz;
                int tint =
                        part.tint >= 0 && part.tint < submit.tintLayers().length
                                ? ARGB.multiply(submit.tintColor(), submit.tintLayers()[part.tint])
                                : submit.tintColor();
                context.owner.observe(instance.handle, part.prototype, source.x, source.y, source.z,
                                      m, material.texture(), material.flags(), tint, instance.uv);
            }
            ++submits;
            groups += geometry.parts.size();
            skippedVertices += geometry.count;
            source.meshFrame = context.frame;
            return true;
        } catch (RuntimeException failure) {
            DynamicCapture.fail(failure);
            return true;
        }
    }

    static final class Instance {
        final InstanceCapture.Instance handle;
        final float[] affine = new float[12], uv = {1, 1, 0, 0};
        Instance(InstanceCapture.Instance handle) {
            this.handle = handle;
        }
    }
    private static final class Context {
        final InstanceCapture owner;
        final HashMap<Object, Geometry> geometries = new HashMap<>();
        final ReferenceQueue<Mesh> collected = new ReferenceQueue<>();
        final Probe probe = new Probe();
        long frame;
        Context(InstanceCapture owner) {
            this.owner = owner;
        }
        Geometry geometry(Mesh mesh) {
            probe.mesh = mesh;
            Geometry found = geometries.get(probe);
            probe.mesh = null;
            if (found != null)
                return found;
            Geometry result = new Geometry();
            // Only the declared immutable Mesh contract permits this one-time source read.
            mesh.forEach(quad -> {
                if (quad.tintIndex() < -1)
                    throw new IllegalArgumentException("Invalid mesh tint index");
                if (result.parts.isEmpty() || result.parts.getLast().tint != quad.tintIndex() ||
                    result.parts.getLast().layer != quad.chunkLayer())
                    result.parts.add(new Part(quad.tintIndex(), quad.chunkLayer()));
                Part part = result.parts.getLast();
                part.reserveQuad();
                for (int v = 0; v < 4; ++v) {
                    int argb = quad.color(v);
                    part.vertices.putFloat(quad.x(v)).putFloat(quad.y(v)).putFloat(quad.z(v));
                    part.vertices.put((byte)(argb >>> 16))
                            .put((byte)(argb >>> 8))
                            .put((byte)argb)
                            .put((byte)(argb >>> 24));
                    part.vertices.putFloat(quad.u(v)).putFloat(quad.v(v));
                }
                result.count += 4;
            });
            geometryVertices += result.count;
            for (Part part : result.parts)
                part.vertices.flip();
            geometries.put(new Key(mesh, collected), result);
            return result;
        }
        void collect() {
            Key key;
            while ((key = (Key)collected.poll()) != null) {
                Geometry removed = geometries.remove(key);
                if (removed != null)
                    for (Part part : removed.parts)
                        if (part.prototype != null)
                            owner.release(part.prototype);
            }
        }
    }
    private static final class Geometry {
        final ArrayList<Part> parts = new ArrayList<>();
        int count;
    }
    private static final class Part {
        final int tint;
        final ChunkSectionLayer layer;
        ByteBuffer vertices = ByteBuffer.allocate(576).order(ByteOrder.LITTLE_ENDIAN);
        InstanceCapture.Prototype prototype;
        Part(int tint, ChunkSectionLayer layer) {
            this.tint = tint;
            this.layer = layer;
        }
        void reserveQuad() {
            if (vertices.remaining() >= 96)
                return;
            var replacement = ByteBuffer.allocate(Math.multiplyExact(vertices.capacity(), 2))
                                      .order(ByteOrder.LITTLE_ENDIAN);
            replacement.put(vertices.flip());
            vertices = replacement;
        }
    }
    private static final class Key extends WeakReference<Mesh> {
        final int hash;
        Key(Mesh mesh, ReferenceQueue<Mesh> queue) {
            super(mesh, queue);
            hash = System.identityHashCode(mesh);
        }
        @Override
        public int hashCode() {
            return hash;
        }
        @Override
        public boolean equals(Object value) {
            return this == value ||
                    value instanceof Key other && get() != null && get() == other.get();
        }
    }
    private static final class Probe {
        Mesh mesh;
        @Override
        public int hashCode() {
            return System.identityHashCode(mesh);
        }
        @Override
        public boolean equals(Object value) {
            return value instanceof Key key && mesh == key.get();
        }
    }
}
