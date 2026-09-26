package dev.primept.capture;

import dev.primept.PrimeClient;
import dev.primept.mixin.SpriteContentsAccessor;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/** The only worker/render synchronization point. Queued arrays own their bytes; no MC pointers escape. */
public final class CaptureInbox {
    private static final long MAX_QUEUE_BYTES = 128L << 20;
    private static final int MAX_BATCHES = 4096;
    private static final int MAX_SECTIONS = 32768;
    private final ArrayDeque<Batch> pending = new ArrayDeque<>();
    private final Map<Long, Long> revisions = new HashMap<>();
    private final Map<Long, Long> chunkRevisions = new HashMap<>();
    private long epoch = 1, revision, bytes;
    private boolean active;
    private RuntimeException failure;
    private Atlas atlas;
    private long atlasVersion;

    public CaptureInbox() { this(Boolean.getBoolean("primept.enabled")); }
    CaptureInbox(boolean active) { this.active = active; }

    public record Token(long epoch, long revision, long chunkRevision, long section, long chunk,
            int x, int y, int z) { }
    public record Batch(long epoch, long section, long revision, boolean removal, List<byte[]> packets, long bytes) { }
    public record Atlas(long version, int width, int height, byte[] rgba) { }
    public record ProfileSnapshot(int sections, int batches, long bytes) { }

    public synchronized Token begin(SectionPos section) {
        if (!active) return null;
        long sequence = (revision += 2);
        long chunk = ChunkPos.pack(section.x(), section.z());
        return new Token(epoch, sequence, chunkRevisions.getOrDefault(chunk, 0L), section.asLong(), chunk,
                section.minBlockX(), section.minBlockY(), section.minBlockZ());
    }

    /** Encoding is worker-private; the monitor protects only admission and publication. */
    public void capture(Token token, SourceQuads source) {
        synchronized (this) { if (!accepts(token)) return; }
        try {
            source.seal();
            var packets = new ArrayList<byte[]>();
            packets.add(Packets.remove(token.epoch, token.section, token.revision));
            for (int layer = SourceQuads.OPAQUE; layer <= SourceQuads.CUTOUT; layer++) {
                ByteBuffer vertices = source.vertices(layer);
                if (vertices.hasRemaining()) packets.add(Packets.mesh(token.epoch, token.section, token.revision + 1,
                        token.x, token.y, token.z, vertices.remaining() / SourceQuads.STRIDE, SourceQuads.STRIDE,
                        0, 12, 16, 4, layer == SourceQuads.CUTOUT ? 1 : 0, layer, vertices));
            }
            synchronized (this) {
                if (!accepts(token)) return;
                revisions.put(token.section, token.revision);
                if (revisions.size() > MAX_SECTIONS) throw new IllegalStateException("Capture section budget exceeded");
                enqueue(token.section, token.revision, false, packets);
            }
        } catch (RuntimeException exception) { captureFailed(token, exception); }
    }

    private boolean accepts(Token token) {
        return token != null && active && token.epoch == epoch
                && token.chunkRevision == chunkRevisions.getOrDefault(token.chunk, 0L)
                && token.revision > revisions.getOrDefault(token.section, 0L);
    }

    public synchronized void captureFailed(Token token, RuntimeException exception) {
        if (token != null && active && token.epoch == epoch
                && token.chunkRevision == chunkRevisions.getOrDefault(token.chunk, 0L)
                && token.revision >= revisions.getOrDefault(token.section, 0L)) fail(exception);
    }

    private void enqueue(long key, long sequence, boolean removal, List<byte[]> packets) {
        long size = packets.stream().mapToLong(packet -> packet.length).sum();
        if (bytes + size > MAX_QUEUE_BYTES || pending.size() >= MAX_BATCHES)
            throw new IllegalStateException("Terrain capture queue exceeded 128 MiB / 4096 batches; renderer disabled to avoid an incomplete scene");
        pending.addLast(new Batch(epoch, key, sequence, removal, List.copyOf(packets), size));
        bytes += size;
    }

    public synchronized Batch poll() {
        while (!pending.isEmpty()) {
            Batch batch = pending.removeFirst();
            bytes -= batch.bytes;
            Long current = revisions.get(batch.section);
            if (batch.epoch != epoch) continue;
            if (batch.removal ? current != null && current > batch.revision
                    : current == null || current != batch.revision) continue;
            return batch;
        }
        return null;
    }

    public synchronized List<Long> sections() { return List.copyOf(revisions.keySet()); }
    public synchronized ProfileSnapshot profileSnapshot() { return new ProfileSnapshot(revisions.size(), pending.size(), bytes); }

    public synchronized void dropChunk(int x, int z) {
        if (!active) return;
        long chunk = ChunkPos.pack(x, z);
        chunkRevisions.put(chunk, ++revision);
        // Bounds tombstones as well as meshes; exceeding this is an explicit fail-closed condition.
        if (chunkRevisions.size() > MAX_SECTIONS) { fail(new IllegalStateException("Chunk generation budget exceeded")); return; }
        try {
            var iterator = revisions.keySet().iterator();
            while (iterator.hasNext()) {
                long key = iterator.next();
                if (SectionPos.x(key) == x && SectionPos.z(key) == z) {
                    iterator.remove();
                    long sequence = ++revision;
                    enqueue(key, sequence, true, List.of(Packets.remove(epoch, key, sequence)));
                }
            }
        } catch (RuntimeException exception) { fail(exception); }
    }

    public synchronized void captureAtlas(SpriteLoader.Preparations preparations) {
        if (!active) return;
        try {
            int width = preparations.width(), height = preparations.height();
            long size = (long) width * height * 4;
            if (size > 256L << 20) throw new IllegalArgumentException("Block atlas exceeds 256 MiB capture budget");
            byte[] rgba = new byte[Math.toIntExact(size)];
            for (var sprite : preparations.regions().values()) {
                var contents = sprite.contents();
                var source = ((SpriteContentsAccessor) contents).primept$originalImage();
                ByteBuffer sourceBytes = source.getPixelBytes();
                int spriteWidth = contents.width(), spriteHeight = contents.height();
                int startX = Math.round(sprite.getU0() * width), startY = Math.round(sprite.getV0() * height);
                // Capture the first source animation frame without evaluating animation or material colors.
                // 26.2 getUniqueFrames() returns [1] for static sprites; only animated entries are frame indices.
                int firstFrame = contents.isAnimated() ? contents.getUniqueFrames().getInt(0) : 0;
                int framesPerRow = source.getWidth() / spriteWidth;
                int sourceX = (firstFrame % framesPerRow) * spriteWidth;
                int sourceY = (firstFrame / framesPerRow) * spriteHeight;
                for (int y = 0; y < spriteHeight; y++) {
                    int offset = ((sourceY + y) * source.getWidth() + sourceX) * 4;
                    sourceBytes.get(offset, rgba, ((startY + y) * width + startX) * 4, spriteWidth * 4);
                }
            }
            // UVs from earlier compiles refer to the old packing; invalidate that entire capture epoch.
            reset();
            atlas = new Atlas(++atlasVersion, width, height, rgba);
            PrimeClient.LOGGER.info("Captured block atlas {}x{}, {} bytes, resource epoch {}", width, height, size, epoch);
        } catch (RuntimeException exception) { fail(exception); }
    }

    public synchronized Atlas atlas() { return atlas; }
    public synchronized long epoch() { return epoch; }
    public synchronized RuntimeException failure() { return failure; }

    public synchronized void reset() {
        ++epoch;
        pending.clear();
        revisions.clear();
        chunkRevisions.clear();
        bytes = 0;
    }

    public synchronized void disable() { active = false; reset(); }
    private void fail(RuntimeException exception) {
        failure = exception;
        disable();
        PrimeClient.LOGGER.error("Prime PT capture disabled", exception);
    }
}
