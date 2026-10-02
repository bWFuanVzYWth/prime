package dev.primept.capture;

import java.lang.foreign.Arena;
import dev.primept.NativeBridge;
import static dev.primept.abi.PrimeAbi.*;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Render-thread-owned prototype and instance changes with reusable typed batch storage. Observing a frame
 * does not publish it: dirty entries survive skipped submissions and are acknowledged after the native batch succeeds.
 * Handles are confined to this resource epoch. No Minecraft types or GPU objects cross this layer.
 */
public final class InstanceCapture implements AutoCloseable {
    private static final int MAX_BYTES = 256 << 20;
    private final Thread owner = Thread.currentThread();
    private final long epoch;
    private long nextPrototypeId = 1, nextInstanceId = 1;
    private final ArrayList<Prototype> dirtyPrototypes = new ArrayList<>();
    private final ArrayList<Instance> dirtyInstances = new ArrayList<>();
    private ArrayList<Instance> previousInstances = new ArrayList<>();
    private ArrayList<Instance> visibleInstances = new ArrayList<>();
    private final FrameContext frame = new FrameContext();
    private long frameNumber, sequence;
    private boolean observing, sealed, closed;
    private Stats stats = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** Owns only reusable wire storage, with a synchronous borrow through acknowledge(). */
    private static final class FrameContext implements AutoCloseable {
        Arena arena;
        MemorySegment memory;
        ByteBuffer bytes;
        int growths;
        void begin(int required) {
            if (required > MAX_BYTES)
                throw new IllegalStateException("Instance delta exceeds 256 MiB");
            if (bytes == null || bytes.capacity() < required) {
                int capacity = (int)Math.min(
                        MAX_BYTES,
                        Math.max(required, bytes == null ? 65536L : (long)bytes.capacity() * 2));
                Arena replacement = Arena.ofConfined();
                try {
                    MemorySegment storage = replacement.allocate(capacity, 8);
                    if (arena != null)
                        arena.close();
                    arena = replacement;
                    memory = storage;
                    bytes = storage.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
                    ++growths;
                } catch (Throwable failure) {
                    replacement.close();
                    throw failure;
                }
            }
            bytes.clear();
        }
        @Override
        public void close() {
            if (arena != null)
                arena.close();
        }
    }

    public static final class Prototype {
        private final InstanceCapture owner;
        private final long id;
        private final int topology, count, stride, position, color, uv;
        private byte[] vertices;
        private int references;
        private boolean owned = true, published, queued = true, retired;
        private Prototype(InstanceCapture owner, long id, int topology, int count, int stride,
                          int position, int color, int uv, byte[] vertices) {
            this.owner = owner;
            this.id = id;
            this.topology = topology;
            this.count = count;
            this.stride = stride;
            this.position = position;
            this.color = color;
            this.uv = uv;
            this.vertices = vertices;
        }
        public long id() {
            return id;
        }
        private boolean needed() {
            return owned || references != 0;
        }
    }

    public static final class Instance {
        private final InstanceCapture owner;
        private final long id;
        private final float[] transform = new float[12], uv = new float[4];
        private Prototype prototype;
        private double x, y, z;
        private int texture, flags, argb;
        private long seen;
        private boolean active, published, queued;
        private Instance(InstanceCapture owner, long id) {
            this.owner = owner;
            this.id = id;
        }
        public long id() {
            return id;
        }
    }

    public record Stats(int activeInstances, int prototypeUpserts, int prototypeRemoves,
                        int instanceUpserts, int instanceRemoves, int bytes, int capacity,
                        int growths, int prototypeBytes) {}

    public InstanceCapture(long epoch) {
        if (epoch <= 0)
            throw new IllegalArgumentException("Invalid resource epoch");
        this.epoch = epoch;
    }

    /** Defines a new immutable local mesh. A source mutation creates a new handle, not an alias. */
    public Prototype prototype(int topology, int count, int stride, int position, int color, int uv,
                               ByteBuffer source) {
        checkMutable();
        if ((topology != 3 && topology != 4) || count <= 0 || count % topology != 0 ||
            stride < 24 || stride > 256 || position < 0 || position > stride - 12 || color < 0 ||
            color > stride - 4 || uv < 0 || uv > stride - 8)
            throw new IllegalArgumentException("Unsupported prototype source layout");
        int size = Math.multiplyExact(count, stride);
        if (size > MAX_BYTES - 104 || source.remaining() != size)
            throw new IllegalArgumentException("Invalid prototype source length");
        byte[] copy = new byte[size];
        source.duplicate().get(copy);
        long id = nextPrototypeId;
        nextPrototypeId = Math.incrementExact(id);
        Prototype result =
                new Prototype(this, id, topology, count, stride, position, color, uv, copy);
        dirtyPrototypes.add(result);
        return result;
    }

    /** Drops the source owner's reference; live instances delay the native removal. */
    public void release(Prototype prototype) {
        checkMutable();
        requirePrototype(prototype);
        prototype.owned = false;
        if (!prototype.needed())
            queue(prototype);
    }

    public Instance instance() {
        checkMutable();
        long id = nextInstanceId;
        nextInstanceId = Math.incrementExact(id);
        return new Instance(this, id);
    }

    public void beginFrame() {
        checkMutable();
        if (observing)
            throw new IllegalStateException("Previous instance observation is still open");
        frameNumber = Math.incrementExact(frameNumber);
        visibleInstances.clear();
        observing = true;
    }

    /**
     * Observes actual source state; exact comparisons avoid silently inventing update tolerances.
     * Origin is stable double-precision world space; transform is row-major local affine 3x4.
     * UV values are scaleU, scaleV, offsetU, offsetV. Source color is Minecraft ARGB.
     */
    public void observe(Instance instance, Prototype prototype, double x, double y, double z,
                        float[] transform, int texture, int flags, int argb, float[] uv) {
        checkMutable();
        if (!observing)
            throw new IllegalStateException("Instance observation is not open");
        if (instance.owner != this)
            throw new IllegalArgumentException("Instance belongs to another context");
        requirePrototype(prototype);
        if (instance.seen == frameNumber)
            throw new IllegalStateException("Instance observed twice in one frame");
        if (transform.length != 12 || uv.length != 4)
            throw new IllegalArgumentException("Invalid instance layout");
        boolean changed = !instance.active || instance.prototype != prototype || instance.x != x ||
                          instance.y != y || instance.z != z || instance.texture != texture ||
                          instance.flags != flags || instance.argb != argb ||
                          !Arrays.equals(instance.transform, transform) ||
                          !Arrays.equals(instance.uv, uv);
        if (!instance.active || instance.prototype != prototype) {
            if (instance.active)
                unreference(instance.prototype);
            ++prototype.references;
            instance.prototype = prototype;
        }
        instance.active = true;
        instance.seen = frameNumber;
        visibleInstances.add(instance);
        if (changed) {
            instance.x = x;
            instance.y = y;
            instance.z = z;
            instance.texture = texture;
            instance.flags = flags;
            instance.argb = argb;
            System.arraycopy(transform, 0, instance.transform, 0, 12);
            System.arraycopy(uv, 0, instance.uv, 0, 4);
            queue(instance);
        }
    }

    public void endFrame() {
        checkMutable();
        if (!observing)
            throw new IllegalStateException("Instance observation is not open");
        for (Instance instance : previousInstances) {
            if (instance.seen != frameNumber) {
                instance.active = false;
                unreference(instance.prototype);
                queue(instance);
            }
        }
        ArrayList<Instance> spare = previousInstances;
        previousInstances = visibleInstances;
        visibleInstances = spare;
        observing = false;
    }

    /** Null means no wire call is required. The returned borrow ends at acknowledge or close. */
    public MemorySegment sealDelta() {
        checkMutable();
        if (observing)
            throw new IllegalStateException("Finish observation before publishing instances");
        int definitions = 0, retirements = 0, updates = 0, removals = 0,
            size = (int)PrimeInstanceBatch.SIZE, sourceBytes = 0;
        for (Prototype prototype : dirtyPrototypes) {
            if (prototype.needed() && !prototype.published) {
                ++definitions;
                size = Math.addExact(
                        size, Math.addExact((int)(PrimePrototypeSource.SIZE + PrimeMeshSpan.SIZE),
                                            prototype.vertices.length));
                sourceBytes = Math.addExact(sourceBytes, prototype.vertices.length);
            } else if (!prototype.needed() && prototype.published) {
                ++retirements;
                size = Math.addExact(size, 16);
            }
        }
        for (Instance instance : dirtyInstances) {
            if (instance.active) {
                ++updates;
                size = Math.addExact(size, 128);
            } else if (instance.published) {
                ++removals;
                size = Math.addExact(size, 16);
            }
        }
        stats = new Stats(previousInstances.size(), definitions, retirements, updates, removals,
                          size == PrimeInstanceBatch.SIZE ? 0 : size,
                          frame.bytes == null ? 0 : frame.bytes.capacity(), frame.growths,
                          sourceBytes);
        if (size == PrimeInstanceBatch.SIZE) {
            // Unpublished objects may disappear before any native submission; retire only Java state.
            completeChanges();
            return null;
        }
        long revision = Math.incrementExact(sequence);
        frame.begin(size);
        var root = frame.memory.asSlice(0, PrimeInstanceBatch.SIZE);
        long prototypeBase = PrimeInstanceBatch.SIZE;
        long prototypeRemovalBase = prototypeBase + definitions * PrimePrototypeSource.SIZE;
        long instanceBase = prototypeRemovalBase + retirements * PrimeRemoval.SIZE;
        long instanceRemovalBase = instanceBase + updates * PrimeInstanceSource.SIZE;
        long spanBase = instanceRemovalBase + removals * PrimeRemoval.SIZE;
        long payloadAt = spanBase + definitions * PrimeMeshSpan.SIZE;
        NativeBridge.header(PrimeInstanceBatch.header(root), PrimeInstanceBatch.SIZE);
        PrimeInstanceBatch.epoch(root, epoch);
        PrimeInstanceBatch.sequence(root, revision);
        PrimeInstanceBatch.prototypes(
                root, frame.memory.asSlice(prototypeBase, definitions * PrimePrototypeSource.SIZE));
        PrimeInstanceBatch.prototype_count(root, definitions);
        PrimeInstanceBatch.prototype_removals(
                root, frame.memory.asSlice(prototypeRemovalBase, retirements * PrimeRemoval.SIZE));
        PrimeInstanceBatch.prototype_removal_count(root, retirements);
        PrimeInstanceBatch.instances(
                root, frame.memory.asSlice(instanceBase, updates * PrimeInstanceSource.SIZE));
        PrimeInstanceBatch.instance_count(root, updates);
        PrimeInstanceBatch.instance_removals(
                root, frame.memory.asSlice(instanceRemovalBase, removals * PrimeRemoval.SIZE));
        PrimeInstanceBatch.instance_removal_count(root, removals);
        int pi = 0, pr = 0, ii = 0, ir = 0;
        for (Prototype prototype : dirtyPrototypes) {
            if (prototype.needed() && !prototype.published) {
                var p = frame.memory.asSlice(prototypeBase + (long)pi * PrimePrototypeSource.SIZE,
                                             PrimePrototypeSource.SIZE);
                var span = frame.memory.asSlice(spanBase + (long)pi * PrimeMeshSpan.SIZE,
                                                PrimeMeshSpan.SIZE);
                ++pi;
                var payload = frame.memory.asSlice(payloadAt, prototype.vertices.length);
                payloadAt += prototype.vertices.length;
                payload.copyFrom(MemorySegment.ofArray(prototype.vertices));
                PrimePrototypeSource.id(p, prototype.id);
                PrimePrototypeSource.revision(p, revision);
                PrimePrototypeSource.spans(p, span);
                PrimePrototypeSource.count(p, 1);
                PrimeMeshSpan.texture_id(span, 0);
                PrimeMeshSpan.flags(span, 0);
                PrimeMeshSpan.topology(span, prototype.topology);
                PrimeMeshSpan.vertex_count(span, prototype.count);
                PrimeMeshSpan.stride(span, prototype.stride);
                PrimeMeshSpan.position_offset(span, prototype.position);
                PrimeMeshSpan.color_offset(span, prototype.color);
                PrimeMeshSpan.uv_offset(span, prototype.uv);
                PrimeByteSpan.data(PrimeMeshSpan.vertices(span), payload);
                PrimeByteSpan.count(PrimeMeshSpan.vertices(span), prototype.vertices.length);
            } else if (!prototype.needed() && prototype.published) {
                var r = frame.memory.asSlice(prototypeRemovalBase + (long)pr++ * PrimeRemoval.SIZE,
                                             PrimeRemoval.SIZE);
                PrimeRemoval.id(r, prototype.id);
                PrimeRemoval.revision(r, revision);
            }
        }
        for (Instance instance : dirtyInstances) {
            if (instance.active) {
                var i = frame.memory.asSlice(instanceBase + (long)ii++ * PrimeInstanceSource.SIZE,
                                             PrimeInstanceSource.SIZE);
                PrimeInstanceSource.id(i, instance.id);
                PrimeInstanceSource.revision(i, revision);
                PrimeInstanceSource.prototype_id(i, instance.prototype.id);
                PrimeInstanceSource.origin(i, 0, instance.x);
                PrimeInstanceSource.origin(i, 1, instance.y);
                PrimeInstanceSource.origin(i, 2, instance.z);
                for (int j = 0; j < 12; j++)
                    PrimeInstanceSource.transform(i, j, instance.transform[j]);
                PrimeInstanceSource.texture_id(i, instance.texture);
                PrimeInstanceSource.flags(i, instance.flags);
                int rgba = (instance.argb & 0xff00ff00) | ((instance.argb >>> 16) & 255) |
                           ((instance.argb & 255) << 16);
                PrimeInstanceSource.rgba(i, rgba);
                PrimeInstanceSource.reserved(i, 0);
                for (int j = 0; j < 4; j++)
                    PrimeInstanceSource.uv_transform(i, j, instance.uv[j]);
            } else if (instance.published) {
                var r = frame.memory.asSlice(instanceRemovalBase + (long)ir++ * PrimeRemoval.SIZE,
                                             PrimeRemoval.SIZE);
                PrimeRemoval.id(r, instance.id);
                PrimeRemoval.revision(r, revision);
            }
        }
        if (payloadAt != size)
            throw new IllegalStateException("Instance batch size mismatch");
        ByteBuffer bytes = frame.bytes;
        bytes.position(size);
        stats = new Stats(stats.activeInstances, definitions, retirements, updates, removals, size,
                          bytes.capacity(), frame.growths, sourceBytes);
        sealed = true;
        return root.asReadOnly();
    }

    /** The native typed batch is atomic; acknowledge after that call succeeds, independently of GPU completion. */
    public void acknowledge() {
        checkOwner();
        if (!sealed)
            throw new IllegalStateException("No sealed instance delta");
        sequence = Math.incrementExact(sequence);
        completeChanges();
        sealed = false;
    }

    public Stats stats() {
        return stats;
    }

    private void completeChanges() {
        for (Instance instance : dirtyInstances) {
            instance.published = instance.active;
            instance.queued = false;
        }
        dirtyInstances.clear();
        for (Prototype prototype : dirtyPrototypes) {
            prototype.published = prototype.needed();
            prototype.retired = !prototype.needed();
            prototype.vertices = null;
            prototype.queued = false;
        }
        dirtyPrototypes.clear();
    }

    private void unreference(Prototype prototype) {
        --prototype.references;
        if (!prototype.needed())
            queue(prototype);
    }
    private void queue(Prototype prototype) {
        if (!prototype.queued) {
            prototype.queued = true;
            dirtyPrototypes.add(prototype);
        }
    }
    private void queue(Instance instance) {
        if (!instance.queued) {
            instance.queued = true;
            dirtyInstances.add(instance);
        }
    }
    private void requirePrototype(Prototype prototype) {
        if (prototype.owner != this || prototype.retired)
            throw new IllegalArgumentException(
                    "Prototype is retired or belongs to another context");
    }
    private void checkMutable() {
        checkOwner();
        if (sealed)
            throw new IllegalStateException("Acknowledge the sealed delta before mutating capture");
    }
    private void checkOwner() {
        if (owner != Thread.currentThread())
            throw new IllegalStateException("Instance capture called off its owner thread");
        if (closed)
            throw new IllegalStateException("Instance capture is closed");
    }
    @Override
    public void close() {
        if (closed)
            return;
        checkOwner();
        frame.close();
        dirtyPrototypes.clear();
        dirtyInstances.clear();
        visibleInstances.clear();
        previousInstances.clear();
        closed = true;
    }
}
