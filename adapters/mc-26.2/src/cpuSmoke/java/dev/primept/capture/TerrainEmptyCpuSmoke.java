package dev.primept.capture;

import dev.primept.PrimeClient;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Real empty source metadata and transformed compiler; no game loop, device or window. */
final class TerrainEmptyCpuSmoke {
    static void run() throws Exception {
        for (Class<?> type :
             new Class<?>[] {net.minecraft.client.renderer.chunk.RenderRegionCache.class,
                             RenderSectionRegion.class, SectionCopy.class, LevelChunkSection.class,
                             LevelChunk.class, ChunkAccess.class}) {
            if (!BlockEntityCandidates.known(type)) {
                for (var method : type.getDeclaredMethods())
                    for (var annotation : method.getDeclaredAnnotations())
                        System.out.println(type.getName() + ": " + annotation);
                throw new AssertionError("Unexpected transformed empty-source dependency: " +
                                         type.getName());
            }
        }
        var constructor = ExclusiveTerrainCapture.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        PrimeClient.CAPTURE.enable();
        var level = blank(ClientLevel.class);
        try (var owner = constructor.newInstance()) {
            field(ExclusiveTerrainCapture.class, "router")
                    .set(owner, new TerrainRouter(PrimeClient.CAPTURE, null, null, null));
            @SuppressWarnings("unchecked")
            var chunks = (Long2ObjectLinkedOpenHashMap<LevelChunk>)field(
                                 ExclusiveTerrainCapture.class, "chunks")
                                 .get(owner);
            var work = (SectionChanges)field(ExclusiveTerrainCapture.class, "work").get(owner);
            var empty = (LongOpenHashSet)field(ExclusiveTerrainCapture.class, "observedEmpty")
                                .get(owner);
            // A full entering strip at render distance 16, including all actual vertical positions.
            for (int x = -16; x <= 16; x++) {
                var chunk = chunk(level, x, 0);
                chunks.put(ChunkPos.pack(x, 0), chunk);
                for (int y = -4; y <= 19; y++)
                    work.add(SectionPos.asLong(x, y, 0));
            }
            owner.prepareSections(false);
            var first = PrimeClient.CAPTURE.seal();
            check(first.batches().size() == 33 * 24 && empty.size() == 33 * 24,
                  "All actual empty positions published exactly once without snapshot/compile");
            for (var batch : first.batches()) {
                var packet =
                        ByteBuffer.wrap(batch.packets().getFirst()).order(ByteOrder.LITTLE_ENDIAN);
                check(packet.remaining() == 72 && packet.getInt(8) == 12 && packet.getInt(64) == 0,
                      "Empty source is a complete op12 publication, never missing availability");
            }
            for (int repeat = 0; repeat < 32; repeat++) {
                for (long key : empty)
                    work.add(key);
                owner.prepareSections(false);
                var next = PrimeClient.CAPTURE.seal();
                check(next.batches().isEmpty() &&
                              next.completedSequence() == first.completedSequence(),
                      "Repeated empty dirtiness has zero source bytes and zero producer tokens");
            }
            var chunk = chunks.get(ChunkPos.pack(0, 0));
            long key = SectionPos.asLong(0, -4, 0);
            check(!ExclusiveTerrainCapture.knownEmpty(chunk, key, true),
                  "Debug synthesis retains actual compiler");
            var section = chunk.getSection(0);
            field(LevelChunkSection.class, "nonEmptyBlockCount").setShort(section, (short)1);
            check(!ExclusiveTerrainCapture.knownEmpty(chunk, key, false),
                  "Air-to-solid is never treated as unchanged empty");
            // Seed an actual old publication, as produced by a nonempty compile.
            empty.remove(key);
            var vertices = new SourceQuads();
            for (int i = 0; i < 4; i++)
                vertices.vertex(0, i & 1, 0, i >> 1, -1, 0, 0);
            PrimeClient.CAPTURE.capture(PrimeClient.CAPTURE.begin(SectionPos.of(key)), vertices);
            PrimeClient.CAPTURE.seal();
            field(LevelChunkSection.class, "nonEmptyBlockCount").setShort(section, (short)0);
            work.add(key);
            owner.prepareSections(false);
            var removed = PrimeClient.CAPTURE.seal();
            check(removed.batches().size() == 1 && removed.batches().getFirst().bytes() == 72,
                  "Solid-to-air atomically clears the previous geometry");
            empty.clear(); // Epoch/resource retirement discards the same owner-held proof set.
            work.add(key);
            owner.prepareSections(false);
            check(PrimeClient.CAPTURE.seal().batches().size() == 1,
                  "A retired proof cannot suppress a new publication");
        } finally {
            PrimeClient.CAPTURE.reset();
        }
        System.out.println(
                "PRIME_PT_TERRAIN_EMPTY_CPU_OK: 792 actual positions; 32 repeated dirty strips = 0 packets/workspaces; debug/nonempty fallback; solid-to-air replacement; proof retirement");
    }

    static void foreign() throws Exception {
        check(ExclusiveTerrainCapture.knownEmpty(chunk(blank(ClientLevel.class), 0, 0),
                                                 SectionPos.asLong(0, -4, 0), false),
              "Downstream compiler hooks cannot disable source routing");
        int before = ForeignHookProbe.terrainCalls;
        run();
        check(ForeignHookProbe.terrainCalls == before, "Java compiler is never entered");
        System.out.println(
                "PRIME_PT_TERRAIN_FOREIGN_CPU_OK: downstream compiler hooks never execute");
    }

    private static RenderSectionRegion airRegion(ClientLevel level, LevelChunk chunk)
            throws Exception {
        var copies = new SectionCopy[27];
        Arrays.fill(copies, new SectionCopy(chunk, 0));
        // Only the immutable all-air source view is used; no world lighting/biome service is needed.
        var region = blank(RenderSectionRegion.class);
        field(RenderSectionRegion.class, "level").set(region, level);
        field(RenderSectionRegion.class, "minSectionX").setInt(region, -1);
        field(RenderSectionRegion.class, "minSectionY").setInt(region, -5);
        field(RenderSectionRegion.class, "minSectionZ").setInt(region, -1);
        field(RenderSectionRegion.class, "sections").set(region, copies);
        return region;
    }

    private static LevelChunk chunk(ClientLevel level, int x, int z) throws Exception {
        var chunk = blank(LevelChunk.class);
        var sections = new LevelChunkSection[24];
        for (int i = 0; i < sections.length; i++)
            sections[i] = blank(LevelChunkSection.class);
        field(ChunkAccess.class, "chunkPos").set(chunk, new ChunkPos(x, z));
        field(ChunkAccess.class, "sections").set(chunk, sections);
        field(ChunkAccess.class, "levelHeightAccessor")
                .set(chunk, LevelHeightAccessor.create(-64, 384));
        field(LevelChunk.class, "level").set(chunk, level);
        field(ChunkAccess.class, "blockEntities").set(chunk, Map.of());
        return chunk;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static <T> T blank(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return type.cast(unsafe.getMethod("allocateInstance", Class.class)
                                 .invoke(field(unsafe, "theUnsafe").get(null), type));
    }
    private static void check(boolean pass, String message) {
        if (!pass)
            throw new AssertionError(message);
    }
}
