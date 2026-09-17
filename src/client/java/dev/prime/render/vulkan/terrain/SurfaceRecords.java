// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.terrain;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renderer-owned exact 32-byte interner. Only the render thread changes records and references.
 * GPU retirement callbacks only enqueue completed leases; draining them is the sole reuse boundary.
 * A lease belongs to a BLAS allocation, so shared voxel instances never release its keys early.
 */
final class SurfaceRecords {
    static final int WORDS = 8;
    private final HashMap<Record, Entry> records = new HashMap<>();
    private final ArrayList<Entry> slots = new ArrayList<>();
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private final HashSet<Entry> dirty = new HashSet<>();
    private final ConcurrentLinkedQueue<Lease> completed = new ConcurrentLinkedQueue<>();
    private final java.util.function.IntUnaryOperator shadowTexture;
    private final HashMap<Integer, TerrainScene.ShadowSurface> shadowSurfaces = new HashMap<>();
    private TerrainScene.ShadowSurfaces shadowSnapshot;
    private long nextGeneration;

    SurfaceRecords() { this(identity -> 0); }

    SurfaceRecords(java.util.function.IntUnaryOperator shadowTexture) {
        this.shadowTexture = shadowTexture;
    }

    TerrainScene.ShadowSurfaces shadowSurfaces() {
        if (this.shadowSnapshot == null) {
            this.shadowSnapshot = new TerrainScene.ShadowSurfaces(this.shadowSurfaces.values().stream()
                    .sorted(java.util.Comparator.comparingInt(TerrainScene.ShadowSurface::key)).toList());
        }
        return this.shadowSnapshot;
    }

    Lease lease() {
        drain();
        return new Lease();
    }

    void drain() {
        for (Lease lease; (lease = this.completed.poll()) != null;) {
            for (Entry entry : lease.entries) {
                if (--entry.references == 0) {
                    this.records.remove(entry.record);
                    this.slots.set(entry.key, null);
                    this.dirty.remove(entry);
                    this.free.addLast(entry.key);
                    if (this.shadowSurfaces.remove(entry.key) != null) this.shadowSnapshot = null;
                }
            }
            lease.entries.clear();
        }
    }

    int size() { return this.records.size(); }
    int extent() { return this.slots.size(); }

    List<Entry> dirty() {
        return this.dirty.stream().sorted(java.util.Comparator.comparingInt(e -> e.key)).toList();
    }

    void uploaded(List<Entry> entries) {
        // AbstractSet.removeAll scans the list when sizes are equal (the normal full upload),
        // making publication quadratic. Remove by hash to keep work linear in uploaded records.
        for (Entry entry : entries) this.dirty.remove(entry);
    }

    final class Lease implements Destroyable {
        private final HashSet<Entry> entries = new HashSet<>();
        private final AtomicBoolean complete = new AtomicBoolean();

        int[] encode(int[] words) {
            if (words.length % WORDS != 0) {
                throw new IllegalArgumentException("Surface records must contain eight words each");
            }
            int[] keys = new int[words.length / WORDS];
            for (int i = 0; i < keys.length; i++) {
                Record record = Record.read(words, i * WORDS);
                Entry entry = records.get(record);
                if (entry == null) {
                    int key = free.isEmpty() ? slots.size() : free.removeFirst();
                    entry = new Entry(key, record);
                    if (key == slots.size()) slots.add(entry);
                    else slots.set(key, entry);
                    records.put(record, entry);
                    dirty.add(entry);
                    int texture = shadowTexture.applyAsInt(record.identity);
                    if (texture != 0) {
                        shadowSurfaces.put(key, new TerrainScene.ShadowSurface(key, texture, ++nextGeneration));
                        shadowSnapshot = null;
                    }
                }
                if (this.entries.add(entry)) entry.references++;
                keys[i] = entry.key;
            }
            return keys;
        }

        /** Called only after the BLAS's final GPU reader, or for a never-submitted allocation. */
        @Override public void destroy() {
            if (this.complete.compareAndSet(false, true)) completed.add(this);
        }
    }

    static final class Entry {
        final int key;
        final Record record;
        int references;
        Entry(int key, Record record) { this.key = key; this.record = record; }
    }

    // Integer equality preserves every source bit, including signed zero and opaque flags.
    record Record(int uv0, int uv1, int uv2, int tint,
            int identity, int flagsEmitter, int uvDensity, int tangent) {
        static Record read(int[] w, int i) {
            return new Record(w[i], w[i + 1], w[i + 2], w[i + 3],
                    w[i + 4], w[i + 5], w[i + 6], w[i + 7]);
        }
        void write(int[] w, int i) {
            w[i] = uv0; w[i + 1] = uv1; w[i + 2] = uv2; w[i + 3] = tint;
            w[i + 4] = identity; w[i + 5] = flagsEmitter;
            w[i + 6] = uvDensity; w[i + 7] = tangent;
        }
    }
}
