package dev.primept.capture;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.primept.PrimeClient;
import dev.primept.mixin.BufferBuilderAccessor;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Actual named custom output, without replaying callbacks or inventing identities for merged draws. */
public final class NamedRawCapture {
    private static final float[] AFFINE = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    private static final float[] UV = {1, 1, 0, 0};
    private static final HashMap<Key, Owner> owners = new HashMap<>();
    private static final ReferenceQueue<ModelCapture.Source> collected = new ReferenceQueue<>();
    private static final ArrayList<Owner> touched = new ArrayList<>();
    private static ArrayList<Scope> previous = new ArrayList<>(), visible = new ArrayList<>();
    private static InstanceCapture instances;
    private static long frame;
    private static double x, y, z;
    private static boolean frozen;
    private NamedRawCapture() {}

    static void begin(InstanceCapture context) {
        instances = context;
        ++frame;
        frozen = false;
        touched.clear();
        visible.clear();
        Key key;
        while ((key = (Key)collected.poll()) != null) {
            Owner owner = owners.remove(key);
            if (owner != null)
                reset(owner);
        }
    }
    public static void origin(double cameraX, double cameraY, double cameraZ) {
        x = cameraX;
        y = cameraY;
        z = cameraZ;
    }
    static void visit(ModelCapture.Source source) {
        if (frozen) {
            DynamicCapture.fail(
                    new IllegalStateException("Custom source collection after preparation"));
            return;
        }
        if (source.customFrame != frame) {
            source.customFrame = frame;
            source.customVisits = 0;
        }
        ++source.customVisits;
        if (source.customOwner != null) {
            touch(source.customOwner);
            source.customOwner.visits = source.customVisits;
        }
    }
    static Emission tag(ModelCapture.Source source, RenderType type, Class<?> callback) {
        if (frozen) {
            DynamicCapture.fail(new IllegalStateException("Custom submit after preparation"));
            return null;
        }
        Owner owner = source.customOwner;
        if (owner == null) {
            owner = new Owner();
            source.customOwner = owner;
            owners.put(new Key(source, collected), owner);
        }
        touch(owner);
        owner.visits = source.customVisits;
        Emission emission = new Emission(owner, type, callback);
        owner.emissions.add(emission);
        return emission;
    }
    private static void touch(Owner owner) {
        if (owner.frame == frame)
            return;
        owner.frame = frame;
        owner.visits = 0;
        owner.emissions.clear();
        touched.add(owner);
    }
    /** Called after real phase sorting and before any renderer starts preparing its groups. */
    public static void freeze(List<? extends SubmitNode> submits) {
        if (!DynamicCapture.active() || frozen)
            return;
        frozen = true;
        if (touched.isEmpty())
            return;
        for (SubmitNode submit : submits)
            if (submit instanceof CustomSubmission attached) {
                Emission emission = attached.primept$customEmission();
                if (emission != null && emission.frame == frame)
                    ++emission.scheduled;
            }
        for (Owner owner : touched) {
            boolean unique = owner.visits == 1;
            for (int i = 0; unique && i < owner.emissions.size(); ++i) {
                Emission emission = owner.emissions.get(i);
                if (emission.scheduled > 1) {
                    unique = false;
                    break;
                }
                for (int j = 0; j < i; ++j)
                    if (emission.matches(owner.emissions.get(j))) {
                        unique = false;
                        break;
                    }
            }
            if (!unique) {
                reset(owner);
                continue;
            }
            boolean same = owner.signatures.size() == owner.emissions.size();
            for (int i = 0; same && i < owner.emissions.size(); ++i)
                same = owner.emissions.get(i).matches(owner.signatures.get(i));
            if (!same) {
                reset(owner);
                for (Emission emission : owner.emissions) {
                    owner.signatures.add(new Signature(emission.type, emission.callback));
                    owner.scopes.add(new Scope());
                }
            }
            for (int i = 0; i < owner.emissions.size(); ++i)
                owner.emissions.get(i).scope = owner.scopes.get(i);
        }
    }
    public static Token enter(Emission emission, VertexConsumer consumer) {
        if (emission == null || emission.frame != frame || !DynamicCapture.active() ||
            !PrimeClient.exclusiveFrameReady())
            return null;
        if (!frozen || emission.scheduled != 1 || emission.scope == null)
            return null;
        if (++emission.attempts != 1) {
            DynamicCapture.fail(
                    new IllegalStateException("Custom emission outside frozen schedule"));
            return null;
        }
        if (consumer.getClass() != BufferBuilder.class)
            return null;
        BufferBuilder buffer = (BufferBuilder)consumer;
        var material = DynamicCapture.material(buffer);
        if (material == null || material.particle() || material.flags() < 0 || material.flags() > 2)
            return null;
        Token token = new Token(emission.scope, buffer,
                                ((BufferBuilderAccessor)buffer).primept$vertices());
        DynamicCapture.named(buffer, token);
        return token;
    }
    public static void leave(Token token, boolean completed) {
        if (!completed && DynamicCapture.active())
            DynamicCapture.fail(
                    new IllegalStateException("Custom source callback did not complete"));
        if (token == null || token.flushed)
            return;
        try {
            token.end = ((BufferBuilderAccessor)token.buffer).primept$vertices();
            token.completed = completed;
        } finally {
            token.buffer = null;
        }
    }
    /** Borrows MeshData only during this flush. In-flight or unsupported spans retain their raw path. */
    static DynamicCapture.ExcludedRanges route(MeshData data, DynamicCapture.Material material,
                                               List<Token> tokens,
                                               DynamicCapture.ExcludedRanges excluded) {
        if (tokens == null)
            return excluded;
        int last = 0, count = data.drawState().vertexCount();
        if (excluded != null)
            excluded.validate(count);
        for (Token token : tokens) {
            if (token.buffer != null) {
                token.flushed = true;
                token.buffer = null;
                continue;
            }
            if (!token.completed || token.end == token.start)
                continue;
            if (token.start < last || token.end > count || token.end < token.start)
                throw new IllegalStateException("Invalid custom emission interval");
            last = token.end;
            if (token.start % 4 != 0 || token.end % 4 != 0)
                continue;
            var remaining = new DynamicCapture.ExcludedRanges();
            int cursor = token.start;
            if (excluded != null)
                for (int i = 0; i < excluded.size && cursor < token.end; i += 2) {
                    int start = excluded.ranges[i], end = excluded.ranges[i + 1];
                    if (end <= cursor)
                        continue;
                    if (start >= token.end)
                        break;
                    if (start > cursor)
                        remaining.add(cursor, Math.min(start, token.end));
                    cursor = Math.max(cursor, end);
                }
            if (cursor < token.end)
                remaining.add(cursor, token.end);
            if (remaining.size == 0 || !observe(token.scope, data, remaining, material))
                continue;
            if (excluded == null)
                excluded = new DynamicCapture.ExcludedRanges();
            excluded.add(token.start, token.end);
        }
        return excluded;
    }
    private static boolean observe(Scope scope, MeshData data, DynamicCapture.ExcludedRanges ranges,
                                   DynamicCapture.Material material) {
        var state = data.drawState();
        var format = state.format();
        int stride = format.getVertexSize();
        if (!state.primitiveTopology().name().equals("QUADS") || stride < 24 || stride > 256 ||
            !format.contains("Position") || !format.contains("Color") || !format.contains("UV0") ||
            !format.getElement("Position").format().name().equals("RGB32_FLOAT") ||
            !format.getElement("Color").format().name().equals("RGBA8_UNORM") ||
            !format.getElement("UV0").format().name().equals("RG32_FLOAT"))
            return false;
        int position = format.getElement("Position").offset(),
            color = format.getElement("Color").offset(), uv = format.getElement("UV0").offset();
        if (position < 0 || position > stride - 12 || color < 0 || color > stride - 4 || uv < 0 ||
            uv > stride - 8)
            return false;
        ByteBuffer source = data.vertexBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int vertices = 0;
        for (int i = 0; i < ranges.size; i += 2)
            vertices = Math.addExact(vertices, ranges.ranges[i + 1] - ranges.ranges[i]);
        int size = Math.multiplyExact(vertices, stride);
        if (size > (256 << 20) - 104)
            return false;
        boolean same = scope.bytes != null && scope.bytes.byteSize() == size &&
                       scope.stride == stride && scope.position == position &&
                       scope.color == color && scope.uv == uv;
        ByteBuffer previousBytes = same ? scope.bytes.bytes() : null;
        int offset = 0;
        for (int i = 0; i < ranges.size; i += 2)
            for (int vertex = ranges.ranges[i]; vertex < ranges.ranges[i + 1]; ++vertex) {
                int at = vertex * stride;
                for (int axis = 0; axis < 3; ++axis) {
                    float value = source.getFloat(at + position + axis * 4);
                    if (!Float.isFinite(value) || Math.abs(value) > 4096)
                        return false;
                }
                if (!Float.isFinite(source.getFloat(at + uv)) ||
                    !Float.isFinite(source.getFloat(at + uv + 4)))
                    return false;
                if (same) {
                    for (int axis = 0; same && axis < 3; ++axis)
                        same = source.getInt(at + position + axis * 4) ==
                               previousBytes.getInt(offset + position + axis * 4);
                    same = same &&
                           source.getInt(at + color) == previousBytes.getInt(offset + color) &&
                           source.getInt(at + uv) == previousBytes.getInt(offset + uv) &&
                           source.getInt(at + uv + 4) == previousBytes.getInt(offset + uv + 4);
                }
                offset += stride;
            }
        if (!same) {
            ByteBuffer[] parts = new ByteBuffer[ranges.size / 2];
            for (int i = 0; i < ranges.size; i += 2)
                parts[i / 2] = source.slice(ranges.ranges[i] * stride,
                                            (ranges.ranges[i + 1] - ranges.ranges[i]) * stride);
            var owned = InstanceCapture.OwnedVertices.copyOf(parts);
            var prototype = instances.prototype(4, vertices, stride, position, color, uv, owned);
            release(scope);
            scope.prototype = prototype;
            scope.bytes = owned;
            scope.stride = stride;
            scope.position = position;
            scope.color = color;
            scope.uv = uv;
        }
        if (scope.instance == null)
            scope.instance = instances.instance();
        instances.observe(scope.instance, scope.prototype, x, y, z, AFFINE, material.texture(),
                          material.flags(), -1, UV);
        scope.seen = frame;
        visible.add(scope);
        return true;
    }
    static void end() {
        for (Scope scope : previous)
            if (scope.seen != frame) {
                release(scope);
                scope.instance = null;
            }
        ArrayList<Scope> spare = previous;
        previous = visible;
        visible = spare;
        touched.clear();
    }
    private static void reset(Owner owner) {
        for (Scope scope : owner.scopes) {
            release(scope);
            scope.instance = null;
        }
        owner.scopes.clear();
        owner.signatures.clear();
    }
    private static void release(Scope scope) {
        if (scope.prototype != null)
            instances.release(scope.prototype);
        scope.prototype = null;
        scope.bytes = null;
    }
    static void close() {
        // A live entity may retain Source.customOwner after the renderer closes. Clear its payloads
        // as well as the registry. The whole epoch closes next; release() is illegal on a sealed batch.
        for (Owner owner : owners.values()) {
            for (Scope scope : owner.scopes) {
                scope.prototype = null;
                scope.bytes = null;
                scope.instance = null;
            }
            owner.scopes.clear();
            owner.signatures.clear();
            owner.emissions.clear();
        }
        owners.clear();
        touched.clear();
        previous.clear();
        visible.clear();
        while (collected.poll() != null) {}
        instances = null;
        frozen = false;
    }
    static final class Owner {
        long frame;
        int visits;
        final ArrayList<Emission> emissions = new ArrayList<>();
        final ArrayList<Signature> signatures = new ArrayList<>();
        final ArrayList<Scope> scopes = new ArrayList<>();
    }
    private record Signature(RenderType type, Class<?> callback) {}
    public static final class Emission {
        final Owner owner;
        final RenderType type;
        final Class<?> callback;
        final long frame = NamedRawCapture.frame;
        Scope scope;
        int scheduled, attempts;
        Emission(Owner owner, RenderType type, Class<?> callback) {
            this.owner = owner;
            this.type = type;
            this.callback = callback;
        }
        boolean matches(Emission other) {
            return type == other.type && callback == other.callback;
        }
        boolean matches(Signature other) {
            return type == other.type && callback == other.callback;
        }
    }
    private static final class Scope {
        InstanceCapture.Instance instance;
        InstanceCapture.Prototype prototype;
        InstanceCapture.OwnedVertices bytes;
        int stride, position, color, uv;
        long seen;
    }
    public static final class Token {
        final Scope scope;
        final int start;
        BufferBuilder buffer;
        int end;
        boolean completed, flushed;
        Token(Scope scope, BufferBuilder buffer, int start) {
            this.scope = scope;
            this.buffer = buffer;
            this.start = start;
        }
    }
    private static final class Key extends WeakReference<ModelCapture.Source> {
        final int hash;
        Key(ModelCapture.Source source, ReferenceQueue<ModelCapture.Source> queue) {
            super(source, queue);
            hash = System.identityHashCode(source);
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
}
