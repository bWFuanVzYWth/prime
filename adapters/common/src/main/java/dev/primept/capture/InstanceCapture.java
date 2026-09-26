package dev.primept.capture;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Render-thread-owned resource, residency and packet contexts. Observing a frame does not publish
 * it: dirty entries survive skipped native submissions and are acknowledged only after op7 succeeds.
 * Handles are confined to this resource epoch. No Minecraft types or GPU objects cross this layer.
 */
public final class InstanceCapture implements AutoCloseable {
    private static final int MAX_BYTES = 256 << 20;
    private final Thread owner = Thread.currentThread();
    private final long epoch;
    private final ResourceContext resources = new ResourceContext();
    private final WorldContext world = new WorldContext();
    private final FrameContext frame = new FrameContext();
    private long frameNumber, sequence;
    private boolean observing, sealed, closed;
    private Stats stats = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** Geometry ownership and queued definition/retirement changes, independent of visibility. */
    private static final class ResourceContext {
        long nextId = 1;
        final ArrayList<Prototype> dirty = new ArrayList<>();
    }

    /** Stable handles and the two visible sets needed to derive explicit removals. */
    private static final class WorldContext {
        long nextId = 1;
        ArrayList<Instance> previous = new ArrayList<>(), current = new ArrayList<>();
        final ArrayList<Instance> dirty = new ArrayList<>();
    }

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
        long id = resources.nextId;
        resources.nextId = Math.incrementExact(id);
        Prototype result =
                new Prototype(this, id, topology, count, stride, position, color, uv, copy);
        resources.dirty.add(result);
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
        long id = world.nextId;
        world.nextId = Math.incrementExact(id);
        return new Instance(this, id);
    }

    public void beginFrame() {
        checkMutable();
        if (observing)
            throw new IllegalStateException("Previous instance observation is still open");
        frameNumber = Math.incrementExact(frameNumber);
        world.current.clear();
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
        world.current.add(instance);
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
        for (Instance instance : world.previous) {
            if (instance.seen != frameNumber) {
                instance.active = false;
                unreference(instance.prototype);
                queue(instance);
            }
        }
        ArrayList<Instance> spare = world.previous;
        world.previous = world.current;
        world.current = spare;
        observing = false;
    }

    /** Null means no wire call is required. The returned borrow ends at acknowledge or close. */
    public MemorySegment sealDelta() {
        checkMutable();
        if (observing)
            throw new IllegalStateException("Finish observation before publishing instances");
        int definitions = 0, retirements = 0, updates = 0, removals = 0, size = 48, sourceBytes = 0;
        for (Prototype prototype : resources.dirty) {
            if (prototype.needed() && !prototype.published) {
                ++definitions;
                size = Math.addExact(size, Math.addExact(56, prototype.vertices.length));
                sourceBytes = Math.addExact(sourceBytes, prototype.vertices.length);
            } else if (!prototype.needed() && prototype.published) {
                ++retirements;
                size = Math.addExact(size, 16);
            }
        }
        for (Instance instance : world.dirty) {
            if (instance.active) {
                ++updates;
                size = Math.addExact(size, 128);
            } else if (instance.published) {
                ++removals;
                size = Math.addExact(size, 16);
            }
        }
        stats = new Stats(world.previous.size(), definitions, retirements, updates, removals,
                          size == 48 ? 0 : size, frame.bytes == null ? 0 : frame.bytes.capacity(),
                          frame.growths, sourceBytes);
        if (size == 48) {
            // Unpublished objects may disappear before any native submission; retire only Java state.
            completeChanges();
            return null;
        }
        long revision = Math.incrementExact(sequence);
        frame.begin(size);
        ByteBuffer bytes = frame.bytes;
        bytes.putInt(Packets.MAGIC)
                .putInt(1)
                .putInt(7)
                .putInt(0)
                .putLong(epoch)
                .putLong(revision)
                .putInt(definitions)
                .putInt(retirements)
                .putInt(updates)
                .putInt(removals);
        for (Prototype prototype : resources.dirty) {
            if (prototype.needed() && !prototype.published) {
                bytes.putLong(prototype.id)
                        .putLong(revision)
                        .putInt(1)
                        .putInt(0)
                        .putInt(0)
                        .putInt(0)
                        .putInt(prototype.topology)
                        .putInt(prototype.count)
                        .putInt(prototype.stride)
                        .putInt(prototype.position)
                        .putInt(prototype.color)
                        .putInt(prototype.uv)
                        .put(prototype.vertices);
            }
        }
        for (Prototype prototype : resources.dirty)
            if (!prototype.needed() && prototype.published)
                bytes.putLong(prototype.id).putLong(revision);
        for (Instance instance : world.dirty) {
            if (!instance.active)
                continue;
            bytes.putLong(instance.id)
                    .putLong(revision)
                    .putLong(instance.prototype.id)
                    .putDouble(instance.x)
                    .putDouble(instance.y)
                    .putDouble(instance.z);
            for (float value : instance.transform)
                bytes.putFloat(value);
            bytes.putInt(instance.texture)
                    .putInt(instance.flags)
                    .put((byte)(instance.argb >>> 16))
                    .put((byte)(instance.argb >>> 8))
                    .put((byte)instance.argb)
                    .put((byte)(instance.argb >>> 24))
                    .putInt(0);
            for (float value : instance.uv)
                bytes.putFloat(value);
        }
        for (Instance instance : world.dirty)
            if (!instance.active && instance.published)
                bytes.putLong(instance.id).putLong(revision);
        if (bytes.position() != size)
            throw new IllegalStateException("Instance packet size mismatch");
        stats = new Stats(stats.activeInstances, definitions, retirements, updates, removals, size,
                          bytes.capacity(), frame.growths, sourceBytes);
        sealed = true;
        return frame.memory.asSlice(0, size).asReadOnly();
    }

    /** Native op7 is atomic; acknowledge after that call succeeds, independently of GPU completion. */
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
        for (Instance instance : world.dirty) {
            instance.published = instance.active;
            instance.queued = false;
        }
        world.dirty.clear();
        for (Prototype prototype : resources.dirty) {
            prototype.published = prototype.needed();
            prototype.retired = !prototype.needed();
            prototype.vertices = null;
            prototype.queued = false;
        }
        resources.dirty.clear();
    }

    private void unreference(Prototype prototype) {
        --prototype.references;
        if (!prototype.needed())
            queue(prototype);
    }
    private void queue(Prototype prototype) {
        if (!prototype.queued) {
            prototype.queued = true;
            resources.dirty.add(prototype);
        }
    }
    private void queue(Instance instance) {
        if (!instance.queued) {
            instance.queued = true;
            world.dirty.add(instance);
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
        resources.dirty.clear();
        world.dirty.clear();
        world.current.clear();
        world.previous.clear();
        closed = true;
    }
}
