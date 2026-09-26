package dev.primept.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import net.minecraft.client.model.geom.ModelPart;

/** Resource lifetime: immutable Vertex values are read only when their references change. */
public final class ModelGeometryContext {
    private final HashMap<Object, Geometry> geometries = new HashMap<>();
    private final ReferenceQueue<ModelPart.Cube> collected = new ReferenceQueue<>();
    private final Probe probe = new Probe();
    private long referenceChecks, geometryReads;
    private final InstanceCapture owner;
    public ModelGeometryContext() {
        this(null);
    }
    public ModelGeometryContext(InstanceCapture owner) {
        this.owner = owner;
    }
    public long referenceChecks() {
        return referenceChecks;
    }
    public long geometryReads() {
        return geometryReads;
    }
    public Geometry observe(ModelPart.Cube cube) {
        probe.cube = cube;
        Geometry previous = geometries.get(probe);
        probe.cube = null;
        if (previous != null && matches(previous, cube.polygons))
            return previous;
        int count = 0;
        for (var polygon : cube.polygons) {
            if (polygon == null || polygon.vertices().length != 4)
                return null;
            count += 4;
        }
        if (count == 0)
            return null;
        var polygons = cube.polygons.clone();
        var vertices = new ModelPart.Vertex[count];
        var bytes = ByteBuffer.allocate(count * 24).order(ByteOrder.LITTLE_ENDIAN);
        int index = 0;
        for (var polygon : polygons)
            for (var vertex : polygon.vertices()) {
                if (vertex == null)
                    return null;
                vertices[index++] = vertex;
                bytes.putFloat(vertex.worldX()).putFloat(vertex.worldY()).putFloat(vertex.worldZ());
                bytes.putInt(-1).putFloat(vertex.u()).putFloat(vertex.v());
            }
        bytes.flip();
        geometryReads += count;
        var result = new Geometry(polygons, vertices, bytes);
        geometries.put(new Key(cube, collected), result);
        if (previous != null && previous.prototype != null)
            owner.release(previous.prototype);
        return result;
    }
    /** CPU source ownership only; native active references and GPU completion still govern retirement. */
    public void collectGarbage() {
        Key key;
        while ((key = (Key)collected.poll()) != null) {
            Geometry geometry = geometries.remove(key);
            if (owner != null && geometry != null && geometry.prototype != null)
                owner.release(geometry.prototype);
        }
    }
    private static final class Key extends WeakReference<ModelPart.Cube> {
        final int hash;
        Key(ModelPart.Cube cube, ReferenceQueue<ModelPart.Cube> queue) {
            super(cube, queue);
            hash = System.identityHashCode(cube);
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
    /** Reused lookup key: no weak-reference allocation on a stable leaf observation. */
    private static final class Probe {
        ModelPart.Cube cube;
        @Override
        public int hashCode() {
            return System.identityHashCode(cube);
        }
        @Override
        public boolean equals(Object value) {
            return value instanceof Key key && cube == key.get();
        }
    }
    private boolean matches(Geometry geometry, ModelPart.Polygon[] polygons) {
        if (polygons.length != geometry.polygons.length)
            return false;
        int index = 0;
        for (int i = 0; i < polygons.length; ++i) {
            ++referenceChecks;
            if (polygons[i] != geometry.polygons[i])
                return false;
            var vertices = polygons[i].vertices();
            if (vertices.length != 4)
                return false;
            for (var vertex : vertices) {
                ++referenceChecks;
                if (vertex != geometry.vertices[index++])
                    return false;
            }
        }
        return true;
    }
    public static final class Geometry {
        final ModelPart.Polygon[] polygons;
        final ModelPart.Vertex[] vertices;
        final ByteBuffer bytes;
        InstanceCapture.Prototype prototype;
        Geometry(ModelPart.Polygon[] polygons, ModelPart.Vertex[] vertices, ByteBuffer bytes) {
            this.polygons = polygons;
            this.vertices = vertices;
            this.bytes = bytes;
        }
        public int vertexCount() {
            return vertices.length;
        }
        public ByteBuffer bytes() {
            return bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        }
    }
}
