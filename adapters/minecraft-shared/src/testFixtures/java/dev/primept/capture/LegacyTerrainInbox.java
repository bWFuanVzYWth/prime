package dev.primept.capture;

import dev.primept.StartupOptions;
import dev.primept.PrimeClient;
import dev.primept.mixin.SpriteContentsAccessor;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/** Finite, coalesced source changes. Tokens prove which source observations can still publish. */
public final class LegacyTerrainInbox {
    private final LinkedHashMap<Long, Batch> pending = new LinkedHashMap<>();
    private final TreeMap<Long, Token> inFlight = new TreeMap<>();
    private final Map<Long, Integer> producers = new HashMap<>();
    private final Map<Long, Long> revisions = new HashMap<>();
    private final Map<Long, List<Long>> chunkSections = new HashMap<>();
    private final Map<Long, Long> chunkRevisions = new HashMap<>();
    private long epoch = 1, revision, bytes;
    private boolean active;
    private boolean resourceActive;
    private RuntimeException failure;
    private Atlas atlas;
    private long atlasVersion;
    private long routeIdentity;
    private final RouteBuffer routeBatch = new RouteBuffer();
    private final List<byte[]> routeResources = new ArrayList<>();
    private final List<Long> routeRetirements = new ArrayList<>();

    public LegacyTerrainInbox() {
        this(StartupOptions.enabled());
    }
    LegacyTerrainInbox(boolean active) {
        this.active = resourceActive = active;
    }

    public record Token(long epoch, long revision, long chunkRevision, long section, long chunk,
                        int x, int y, int z) {}
    public record Batch(long epoch, long section, long revision, boolean removal,
                        List<byte[]> packets, long bytes) {}
    public record Sealed(long epoch, List<Batch> batches, long completedSequence) {}
    public record Atlas(long version, int width, int height, byte[] rgba) {}
    public record ProfileSnapshot(int sections, int batches, long bytes) {}

    public synchronized Token begin(SectionPos section) {
        if (!active)
            return null;
        long sequence = revision = Math.incrementExact(revision);
        long chunk = ChunkPos.pack(section.x(), section.z());
        var token =
                new Token(epoch, sequence, chunkRevisions.getOrDefault(chunk, 0L), section.asLong(),
                          chunk, section.minBlockX(), section.minBlockY(), section.minBlockZ());
        inFlight.put(sequence, token);
        producers.merge(chunk, 1, Integer::sum);
        return token;
    }

    public synchronized void complete(Token token) {
        if (token == null || token.epoch != epoch || inFlight.remove(token.revision) == null)
            return;
        int count = producers.get(token.chunk) - 1;
        if (count == 0) {
            producers.remove(token.chunk);
            chunkRevisions.remove(token.chunk);
        } else
            producers.put(token.chunk, count);
    }

    synchronized long routeIdentity() {
        return routeIdentity = Math.incrementExact(routeIdentity);
    }
    synchronized void routeResource(long sourceEpoch, byte[] packet) {
        if (active && sourceEpoch == epoch) {
            routeResources.add(packet);
            bytes += packet.length;
        }
    }
    synchronized void retireRouteResource(long sourceEpoch, long id) {
        if (active && sourceEpoch == epoch)
            routeRetirements.add(id);
    }
    synchronized void route(Token token, byte[] packet) {
        if (!accepts(token))
            return;
        if (revisions.put(token.section, token.revision) == null)
            chunkSections.computeIfAbsent(token.chunk, ignored -> new ArrayList<>())
                    .add(token.section);
        enqueue(token.section, token.revision, false, List.of(packet));
    }

    /** Encoding is worker-private; the monitor protects only admission and publication. */
    public void capture(Token token, SourceQuads source) {
        try {
            synchronized (this) {
                if (!accepts(token))
                    return;
            }
            source.seal();
            var layers = new ArrayList<Packets.SectionLayer>(3);
            for (int layer = SourceQuads.OPAQUE; layer <= SourceQuads.TRANSLUCENT; layer++) {
                ByteBuffer vertices = source.vertices(layer);
                if (vertices.hasRemaining())
                    layers.add(new Packets.SectionLayer(layer, 1, layer, 4,
                                                        vertices.remaining() / SourceQuads.STRIDE,
                                                        SourceQuads.STRIDE, 0, 12, 16, vertices));
            }
            byte[] packet = Packets.sectionReplace(token.epoch, token.section, token.revision,
                                                   token.x, token.y, token.z, layers);
            synchronized (this) {
                if (!accepts(token))
                    return;
                if (revisions.put(token.section, token.revision) == null)
                    chunkSections.computeIfAbsent(token.chunk, ignored -> new ArrayList<>())
                            .add(token.section);
                enqueue(token.section, token.revision, false, List.of(packet));
            }
        } catch (RuntimeException exception) {
            captureFailed(token, exception);
        } finally {
            complete(token);
        }
    }

    private boolean accepts(Token token) {
        return token != null && active && token.epoch == epoch &&
                inFlight.containsKey(token.revision) &&
                token.chunkRevision == chunkRevisions.getOrDefault(token.chunk, 0L) &&
                token.revision > revisions.getOrDefault(token.section, 0L);
    }

    public synchronized void captureFailed(Token token, RuntimeException exception) {
        if (token != null && active && token.epoch == epoch &&
            inFlight.containsKey(token.revision) &&
            token.chunkRevision == chunkRevisions.getOrDefault(token.chunk, 0L) &&
            token.revision >= revisions.getOrDefault(token.section, 0L))
            fail(exception);
    }

    private void enqueue(long key, long sequence, boolean removal, List<byte[]> packets) {
        long size = packets.stream().mapToLong(packet -> packet.length).sum();
        var old = pending.put(key,
                              new Batch(epoch, key, sequence, removal, List.copyOf(packets), size));
        bytes += size - (old == null ? 0 : old.bytes);
    }

    /** Detach exactly this batch. Later/reentrant events belong to the next call. */
    public synchronized Sealed seal() {
        var batches = new ArrayList<Batch>();
        // Resource packets must survive section coalescing. All definitions precede uses;
        // handle retirements follow every section in this sealed batch.
        var definitions = routeBatch.header(13, epoch).i(0).i(0);
        int definitionCount = 0;
        for (byte[] resource : routeResources) {
            // Each producer packet contains exactly one definition. Batch records, not FFM calls.
            if (definitions.size() > (256 << 20) - (resource.length - 32)) {
                definitions.integerAt(24, definitionCount);
                byte[] packet = definitions.seal();
                batches.add(new Batch(epoch, 0, 0, false, List.of(packet), packet.length));
                definitions.header(13, epoch).i(0).i(0);
                definitionCount = 0;
            }
            definitions.append(resource, 32, resource.length - 32);
            ++definitionCount;
        }
        if (definitionCount != 0) {
            definitions.integerAt(24, definitionCount);
            byte[] packet = definitions.seal();
            batches.add(new Batch(epoch, 0, 0, false, List.of(packet), packet.length));
        }
        routeResources.clear();
        var removals = new ArrayList<Batch>();
        for (var batch : pending.values()) {
            if (batch.removal)
                removals.add(batch);
            else
                batches.add(batch);
        }
        int capacity = ((256 << 20) - 32) / 16;
        for (int first = 0; first < removals.size(); first += capacity) {
            int count = Math.min(capacity, removals.size() - first);
            long[] sections = new long[count], sequences = new long[count];
            for (int i = 0; i < count; i++) {
                var item = removals.get(first + i);
                sections[i] = item.section;
                sequences[i] = item.revision;
            }
            byte[] packet = Packets.removeSections(epoch, sections, sequences);
            batches.add(new Batch(epoch, 0, 0, true, List.of(packet), packet.length));
        }
        int retirementCapacity = ((256 << 20) - 32) / 8;
        for (int first = 0; first < routeRetirements.size(); first += retirementCapacity) {
            int count = Math.min(retirementCapacity, routeRetirements.size() - first);
            var retired = routeBatch.header(13, epoch).i(0).i(count);
            for (int i = 0; i < count; ++i)
                retired.l(routeRetirements.get(first + i));
            byte[] packet = retired.seal();
            batches.add(new Batch(epoch, 0, 0, false, List.of(packet), packet.length));
        }
        routeRetirements.clear();
        pending.clear();
        bytes = 0;
        long completed = inFlight.isEmpty() ? revision : inFlight.firstKey() - 1;
        return new Sealed(epoch, List.copyOf(batches), completed);
    }

    public synchronized List<Long> sections() {
        return List.copyOf(revisions.keySet());
    }
    public synchronized ProfileSnapshot profileSnapshot() {
        return new ProfileSnapshot(revisions.size(), pending.size(), bytes);
    }

    public synchronized void dropChunk(int x, int z) {
        if (!active)
            return;
        long chunk = ChunkPos.pack(x, z);
        boolean producing = producers.containsKey(chunk);
        // No published source or unfinished token: this host unload has no native consumer.
        if (!producing && !chunkSections.containsKey(chunk))
            return;
        revision = Math.incrementExact(revision);
        if (producing)
            chunkRevisions.put(chunk, revision);
        try {
            var sections = chunkSections.remove(chunk);
            if (sections != null)
                for (long key : sections) {
                    revisions.remove(key);
                    long sequence = revision = Math.incrementExact(revision);
                    enqueue(key, sequence, true, List.of());
                }
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    public synchronized void captureAtlas(SpriteLoader.Preparations preparations) {
        if (!resourceActive)
            return;
        try {
            int width = preparations.width(), height = preparations.height();
            long size = (long)width * height * 4;
            if (size > 256L << 20)
                throw new IllegalArgumentException("Block atlas exceeds 256 MiB capture budget");
            byte[] rgba = new byte[Math.toIntExact(size)];
            for (var sprite : preparations.regions().values()) {
                var contents = sprite.contents();
                var source = ((SpriteContentsAccessor)contents).primept$originalImage();
                ByteBuffer sourceBytes = source.getPixelBytes();
                int spriteWidth = contents.width(), spriteHeight = contents.height();
                int startX = Math.round(sprite.getU0() * width),
                    startY = Math.round(sprite.getV0() * height);
                // Capture the first source animation frame without evaluating animation or material colors.
                // 26.2 getUniqueFrames() returns [1] for static sprites; only animated entries are frame indices.
                int firstFrame = contents.isAnimated() ? contents.getUniqueFrames().getInt(0) : 0;
                int framesPerRow = source.getWidth() / spriteWidth;
                int sourceX = (firstFrame % framesPerRow) * spriteWidth;
                int sourceY = (firstFrame / framesPerRow) * spriteHeight;
                for (int y = 0; y < spriteHeight; y++) {
                    int offset = ((sourceY + y) * source.getWidth() + sourceX) * 4;
                    sourceBytes.get(offset, rgba, ((startY + y) * width + startX) * 4,
                                    spriteWidth * 4);
                }
            }
            // UVs from earlier compiles refer to the old packing; invalidate that entire capture epoch.
            reset();
            atlas = new Atlas(++atlasVersion, width, height, rgba);
            PrimeClient.LOGGER.info("Captured block atlas {}x{}, {} bytes, resource epoch {}",
                                    width, height, size, epoch);
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    public synchronized Atlas atlas() {
        return atlas;
    }
    public synchronized long epoch() {
        return epoch;
    }
    public synchronized RuntimeException failure() {
        return failure;
    }

    public synchronized void reset() {
        ++epoch;
        routeResources.clear();
        routeRetirements.clear();
        routeIdentity = 0;
        pending.clear();
        revisions.clear();
        chunkSections.clear();
        chunkRevisions.clear();
        inFlight.clear();
        producers.clear();
        bytes = 0;
    }

    public synchronized void disable() {
        active = false;
        reset();
    }
    public synchronized void enable() {
        reset();
        failure = null;
        active = resourceActive = true;
    }
    /** Drop PT-owned pixel copies while vanilla is selected. Host resource pixels remain host-owned. */
    public synchronized void releaseSources() {
        disable();
        resourceActive = false;
        atlas = null;
        failure = null;
    }
    private void fail(RuntimeException exception) {
        failure = exception;
        disable();
        PrimeClient.LOGGER.error("Prime PT capture disabled", exception);
    }
}
