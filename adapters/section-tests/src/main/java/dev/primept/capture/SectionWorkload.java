package dev.primept.capture;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.primept.NativeBridge;
import java.io.DataOutputStream;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Shared dual-version harness: real palettes, real SectionCompiler, production FFM source route. */
final class SectionWorkload implements AutoCloseable {
    private static final ValueLayout.OfInt I =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong L =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private final SectionCompilerOracle.Case fixture;
    private final Region region;
    private final BlockStateModelSet models;
    private final FluidStateModelSet fluids;
    private final SectionCompiler compiler;
    private final BlockColors colors = SectionTintCases.colors();
    private final SectionBufferBuilderPack builders = new SectionBufferBuilderPack();
    private final List<SectionPos> keys = new ArrayList<>();

    SectionWorkload(SectionCompilerOracle.Case fixture, BlockStateModel empty,
                    FluidStateModelSet fluids) throws Exception {
        this.fixture = fixture;
        if (fixture.blendRadius() >= 0) {
            var water =
                    fluids.get(net.minecraft.world.level.material.Fluids.WATER.defaultFluidState());
            fluids = new FluidStateModelSet(
                    Map.of(), new net.minecraft.client.renderer.block.FluidModel(
                                      water.layer(), water.stillMaterial(), water.flowingMaterial(),
                                      water.overlayMaterial(),
                                      net.minecraft.client.color.block.BlockTintSources.water()));
        }
        this.fluids = fluids;
        models = new BlockStateModelSet(fixture.models(), empty);
        region = FluidRouterCpuSmoke.blank(Region.class);
        region.side = fixture.horizontalSections();
        region.sections = new LevelChunkSection[(region.side + 1) * (region.side + 1) * 3];
        region.tintWorld = SectionTintCases.world(fixture.blendRadius(), fixture.biomePhase());
        for (int x = -1; x < region.side; ++x)
            for (int z = -1; z < region.side; ++z)
                for (int y = -1; y <= 1; ++y) {
                    var key = SectionPos.of(x, y, z);
                    keys.add(key);
                    region.sections[index(x, y, z)] =
                            SectionSourcesCpuSmoke.section(Blocks.AIR.defaultBlockState());
                }
        for (var e :
             fixture.blocks()
                     .entrySet()
                     .stream()
                     .sorted(java.util.Comparator
                                     .<java.util.Map.Entry<BlockPos, BlockState>>comparingInt(
                                             e -> e.getKey().getY())
                                     .thenComparingInt(e -> e.getKey().getZ())
                                     .thenComparingInt(e -> e.getKey().getX()))
                     .toList()) {
            var p = e.getKey();
            if (p.getX() < -16 || p.getX() >= region.side * 16 || p.getY() < -16 || p.getY() > 31 ||
                p.getZ() < -16 || p.getZ() >= region.side * 16)
                throw new AssertionError("Fixture outside closed source volume: " + p);
            region.sections[index(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4)].getStates().set(
                    p.getX() & 15, p.getY() & 15, p.getZ() & 15, e.getValue());
        }
        compiler = new SectionCompiler(false, true, models, fluids, colors);
    }
    private int index(int x, int y, int z) {
        return region.index(x, y, z);
    }

    private void frame(SourcePages pages, long batch, String mode) {
        pages.header(SectionSources.GAME_VERSION, 1, 1, batch)
                .d(0)
                .d(0)
                .i(region.side - 1)
                .i(-1)
                .i(1)
                .i(-1)
                .i(region.side - 1)
                .i(-1)
                .i(region.side - 1)
                .l(batch);
        if (batch == 1)
            for (int x = -1; x < region.side; ++x)
                for (int z = -1; z < region.side; ++z)
                    pages.i(1).i(x).i(0).i(z);
        else if (mode.equals("reload"))
            pages.i(4).i(0).i(0).i(0);
        else if (mode.equals("biome"))
            pages.i(7).i(0).i(0).i(0);
        else if (mode.equals("biome_columns"))
            for (int x = 0; x <= region.side; x += 3)
                for (int z = 0; z <= region.side; z += 3)
                    pages.i(6).i(x).i(0).i(z);
        else if (!mode.equals("idle"))
            for (var k : keys)
                if (k.x() >= 0 && k.y() >= 0 && k.z() >= 0)
                    pages.i(3).i(k.x()).i(k.y()).i(k.z());
        pages.i(0);
    }

    void write(Path directory) throws Exception {
        Files.writeString(directory.resolve(fixture.name() + ".workload.properties"),
                          "requested=" + keys.size() +
                                  "\nedited=" + (region.side * region.side * 2) + "\n");
        var expected = new LegacyTerrainInbox(true);
        try (var source = new SourcePages(); var frame = new SourcePages();
             var bridge =
                     new NativeBridge(Path.of(System.getProperty("primept.smoke.nativeLibrary")));
             var tintResponse = new SourcePages()) {
            bridge.submit(Packets.reset(1));
            bridge.submit(Packets.texture(1, 16, 16, SourceSpriteFixture.atlas()));
            var router = new SectionSources(models, fluids);
            source.header(SectionSources.GAME_VERSION, 2, 1, 1);
            // Source first: the oracle is not allowed to warm source model-selection caches.
            for (var k : keys)
                router.section(source, k.x(), k.y(), k.z(),
                               region.sections[index(k.x(), k.y(), k.z())]);
            source.i(0);
            SectionSourcesCpuSmoke.write(source, directory.resolve(fixture.name() + ".source"));
            frame(frame, 1, "full");
            SectionSourcesCpuSmoke.write(frame, directory.resolve(fixture.name() + ".frame"));
            routeFixture(bridge, frame, source, tintResponse, directory, fixture.name());
            compile(keys, expected, builders);
            if (fixture.name().startsWith("bench_") && !fixture.name().endsWith("_edited")) {
                restore(true);
                for (int batch = 2; batch <= 3; ++batch) {
                    String suffix = batch == 2 ? ".edit" : ".unchanged";
                    frame(frame, batch, "edit");
                    SectionSourcesCpuSmoke.write(
                            frame, directory.resolve(fixture.name() + suffix + ".frame"));
                    source.header(SectionSources.GAME_VERSION, 2, 1, batch);
                    for (var k : keys)
                        if (k.x() >= 0 && k.y() >= 0 && k.z() >= 0)
                            router.section(source, k.x(), k.y(), k.z(),
                                           region.sections[index(k.x(), k.y(), k.z())]);
                    source.i(0);
                    SectionSourcesCpuSmoke.write(
                            source, directory.resolve(fixture.name() + suffix + ".source"));
                    routeFixture(bridge, frame, source, tintResponse, directory,
                                 fixture.name() + suffix);
                }
                restore(false);
            }
        }
        if (fixture.name().startsWith("bench_")) {
            var parallel = new LegacyTerrainInbox(true);
            try (var reference = new Reference(Integer.getInteger("primept.section.threads", 8))) {
                reference.compileBatch(keys, parallel);
            }
            writeExpected(parallel, directory.resolve(fixture.name() + ".parallel.expected"));
        }
        writeExpected(expected, directory.resolve(fixture.name() + ".expected"));
    }
    private void routeFixture(NativeBridge bridge, SourcePages frame, SourcePages source,
                              SourcePages tintResponse, Path directory, String name)
            throws Exception {
        bridge.requestSections(frame);
        var request = bridge.sections(source);
        for (int round = 0; request.byteSize() != 0; ++round) {
            if (round >= 2)
                throw new AssertionError("Extra callback round");
            String suffix = request.get(I, 28) == 3 ? ".biome" : ".tint";
            Files.write(directory.resolve(name + suffix + ".requests"),
                        request.toArray(ValueLayout.JAVA_BYTE));
            region.tintWorld.rejectColorCallbacks = true;
            try {
                SectionTints.respond(request, tintResponse, region, colors, fluids,
                                     region.tintWorld);
            } finally {
                region.tintWorld.rejectColorCallbacks = false;
            }
            SectionSourcesCpuSmoke.write(tintResponse, directory.resolve(name + suffix));
            request = bridge.sections(tintResponse);
        }
        if (nativeCount(bridge.cpuDiagnostics(), "tint_callbacks") != 0)
            throw new AssertionError("Known vanilla tint source invoked a color callback");
    }
    private static void writeExpected(LegacyTerrainInbox expected, Path output) throws Exception {
        var packets =
                expected.seal().batches().stream().flatMap(b -> b.packets().stream()).toList();
        try (var out = new DataOutputStream(Files.newOutputStream(output))) {
            out.writeInt(packets.size());
            for (byte[] packet : packets) {
                out.writeInt(packet.length);
                out.write(packet);
            }
        }
    }

    private long compile(List<SectionPos> selected, LegacyTerrainInbox expected,
                         SectionBufferBuilderPack builders) {
        long vertices = 0;
        for (var k : selected) {
            var result = compiler.compile(k, region, VertexSorting.byDistance(0, 0, 0), builders);
            try {
                var quads = expected == null ? null : new SourceQuads();
                for (var entry : result.renderedLayers.entrySet()) {
                    var mesh = entry.getValue();
                    int count = mesh.drawState().vertexCount();
                    vertices += count;
                    if (quads == null)
                        continue;
                    var format = mesh.drawState().format();
                    var bytes = mesh.vertexBuffer().order(ByteOrder.LITTLE_ENDIAN);
                    int stride = format.getVertexSize(),
                        position = format.getElement("Position").offset(),
                        color = format.getElement("Color").offset(),
                        uv = format.getElement("UV0").offset();
                    for (int v = 0; v < count; ++v) {
                        int at = bytes.position() + v * stride, rgba = bytes.getInt(at + color);
                        int argb =
                                (rgba & 0xff00ff00) | ((rgba & 255) << 16) | ((rgba >>> 16) & 255);
                        quads.vertex(entry.getKey().ordinal(), bytes.getFloat(at + position),
                                     bytes.getFloat(at + position + 4),
                                     bytes.getFloat(at + position + 8), argb,
                                     bytes.getFloat(at + uv), bytes.getFloat(at + uv + 4));
                    }
                }
                if (quads != null)
                    expected.capture(expected.begin(k), quads);
            } finally {
                result.release();
                builders.clearAll();
            }
        }
        return vertices;
    }

    void bench(Path directory) throws Exception {
        int warmup = Integer.getInteger("primept.section.warmup", 8),
            samples = Integer.getInteger("primept.section.samples", 25);
        if (warmup < 1 || samples < 3)
            throw new IllegalArgumentException("Need warmup>=1, samples>=3");
        var rows = new ArrayList<String>();
        rows.add(
                "case,mode,sample,warmup,order,mc_threads,mc_ms,frame_ms,plan_ms,pack_ms,accept_ms,route_ms,request_count,source_bytes,mc_vertices,java_allocated_bytes,gc_count,gc_ms,tint_queries,tint_callback_ms,compiled_sections,native_triangles,workset_sections,native_diagnostics");
        var edited = keys.stream().filter(k -> k.x() >= 0 && k.y() >= 0 && k.z() >= 0).toList();
        var allocation = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if (allocation.isThreadAllocatedMemorySupported())
            allocation.setThreadAllocatedMemoryEnabled(true);
        int threads = Integer.getInteger("primept.section.threads", 8);
        try (var reference = new Reference(threads)) {
            for (String mode :
                 fixture.name().equals("bench_tinted")
                         ? List.of("edit", "unchanged", "idle", "biome", "biome_columns", "reload")
                         : List.of("edit", "unchanged", "idle")) {
                restore(false);
                region.tintWorld.phase = fixture.biomePhase();
                region.tintWorld.columnsOnly = mode.equals("biome_columns");
                region.tintWorld.clearColors();
                try (var bridge = new NativeBridge(
                             Path.of(System.getProperty("primept.smoke.nativeLibrary")));
                     var events = new SourcePages(); var response = new SourcePages()) {
                    bridge.submit(Packets.reset(1));
                    bridge.submit(Packets.texture(1, 16, 16, SourceSpriteFixture.atlas()));
                    var router = new SectionSources(models, fluids);
                    for (int sample = -1; sample < warmup + samples; ++sample) {
                        long batch = sample + 2L;
                        if (mode.startsWith("biome"))
                            region.tintWorld.phase = (sample & 1);
                        if (mode.equals("edit") && sample >= 0)
                            restore((sample & 1) == 0);
                        var selected = sample < 0 || mode.equals("reload") ? keys
                                       : mode.equals("idle")               ? List.<SectionPos>of()
                                                                           : edited;
                        // Alternate order to avoid always timing one compiler after the other's cache warming.
                        boolean mcFirst = (sample & 1) == 0;
                        long[] mc = new long[2];
                        if (mcFirst) {
                            invalidateColors(mode);
                            reference.measure(selected, mc);
                        }
                        invalidateColors(mode);
                        long gc0 = gcCount(), gcMs0 = gcMillis(), alloc0 = allocated(allocation);
                        long t0 = System.nanoTime();
                        frame(events, batch, mode);
                        long t1 = System.nanoTime();
                        var request = bridge.requestSections(events);
                        long t2 = System.nanoTime();
                        if (mode.equals("reload"))
                            router = new SectionSources(models, fluids);
                        long count = request.get(L, 8);
                        response.header(SectionSources.GAME_VERSION, 2, 1, batch);
                        for (long n = 0; n < count; ++n) {
                            long at = 32 + n * 16;
                            int x = request.get(I, at), y = request.get(I, at + 4),
                                z = request.get(I, at + 8);
                            router.section(response, x, y, z, region.sections[index(x, y, z)]);
                        }
                        response.i(0);
                        long t3 = System.nanoTime();
                        long sourceBytes = response.bytes();
                        var tints = bridge.sections(response);
                        long tintCount = 0, tintNanos = 0;
                        for (int round = 0; tints.byteSize() != 0; ++round) {
                            if (round >= 2)
                                throw new AssertionError("Unexpected tint continuation");
                            tintCount += tints.get(L, 8);
                            long tintStart = System.nanoTime();
                            SectionTints.respond(tints, response, region, colors, fluids,
                                                 region.tintWorld);
                            tintNanos += System.nanoTime() - tintStart;
                            sourceBytes += response.bytes();
                            tints = bridge.sections(response);
                        }
                        long t4 = System.nanoTime();
                        long bytes = alloc0 < 0 ? -1 : allocated(allocation) - alloc0,
                             gc = gcCount() - gc0, gcMs = gcMillis() - gcMs0;
                        if (!mcFirst) {
                            invalidateColors(mode);
                            reference.measure(selected, mc);
                        }
                        String diagnostics =
                                bridge.cpuDiagnostics(); // formatting and I/O are outside timed work
                        long expectedRequests = sample < 0 || mode.equals("reload") ? keys.size()
                                                : (mode.equals("idle") || mode.startsWith("biome"))
                                                        ? 0
                                                        : edited.size();
                        if (count != expectedRequests)
                            throw new AssertionError("Unexpected workset " + diagnostics);
                        int compiled = sample < 0 || mode.equals("reload") ? keys.size()
                                       : (mode.equals("edit") || mode.startsWith("biome"))
                                               ? edited.size()
                                               : 0;
                        if (!diagnostics.contains("compiled=" + compiled + " "))
                            throw new AssertionError(diagnostics);
                        rows.add(String.format(
                                Locale.ROOT,
                                "%s,%s,%d,%s,%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%d,%d,%d,%d,%d,%.6f,%d,%d,%d,\"%s\"",
                                fixture.name(), sample < 0 ? "cold_" + mode : mode, sample,
                                sample < warmup, mcFirst ? "mc_first" : "native_first", threads,
                                mc[0] / 1e6, (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6,
                                (t4 - t3) / 1e6, (t4 - t0) / 1e6, count, sourceBytes, mc[1], bytes,
                                gc, gcMs, tintCount, tintNanos / 1e6, compiled,
                                nativeCount(diagnostics, "triangles"), edited.size(), diagnostics));
                    }
                }
            }
        }
        restore(false);
        Files.write(directory.resolve(fixture.name() + ".csv"), rows);
    }
    private void invalidateColors(String mode) {
        if (mode.equals("biome_columns"))
            region.tintWorld.clearColumns(region.side);
        else if (mode.equals("biome") || mode.equals("reload"))
            region.tintWorld.clearColors();
    }
    private void restore(boolean edit) {
        for (int x = 0; x < region.side; ++x)
            for (int z = 0; z < region.side; ++z)
                for (int y = 0; y < 2; ++y) {
                    var pos = new BlockPos(x * 16 + 8, y * 16 + 8, z * 16 + 8);
                    var original =
                            fixture.blocks().getOrDefault(pos, Blocks.AIR.defaultBlockState());
                    region.sections[index(x, y, z)].getStates().set(
                            8, 8, 8,
                            !edit              ? original
                            : original.isAir() ? Blocks.STONE.defaultBlockState()
                                               : Blocks.AIR.defaultBlockState());
                }
    }
    private static long nativeCount(String diagnostics, String name) {
        String marker = " " + name + "=";
        int start =
                diagnostics.indexOf(marker, diagnostics.indexOf("mc_source[")) + marker.length();
        return Long.parseLong(diagnostics.substring(start, diagnostics.indexOf(' ', start)));
    }
    /** Test-only parallel reference, one complete section per job. Never part of the mod JAR. */
    private final class Reference implements AutoCloseable {
        private final java.util.concurrent.ExecutorService workers;
        private final SectionBufferBuilderPack[] packs;
        Reference(int threads) {
            if (threads < 1)
                throw new IllegalArgumentException("Reference threads must be positive");
            workers = threads == 1 ? null
                                   : java.util.concurrent.Executors.newFixedThreadPool(threads);
            packs = new SectionBufferBuilderPack[threads];
            for (int i = 0; i < threads; ++i)
                packs[i] = new SectionBufferBuilderPack();
        }
        void measure(List<SectionPos> selected, long[] result) throws Exception {
            long start = System.nanoTime();
            result[1] = compileBatch(selected, null);
            result[0] = System.nanoTime() - start;
        }
        long compileBatch(List<SectionPos> selected, LegacyTerrainInbox expected) throws Exception {
            if (workers == null || selected.isEmpty())
                return compile(selected, expected, packs[0]);
            // Each task owns one builder for this closed batch; join before editing input again.
            var tasks = new ArrayList<java.util.concurrent.Callable<Long>>();
            for (int i = 0; i < Math.min(packs.length, selected.size()); ++i) {
                int lane = i;
                tasks.add(() -> {
                    long vertices = 0;
                    for (int n = lane; n < selected.size(); n += packs.length)
                        vertices += compile(selected.subList(n, n + 1), expected, packs[lane]);
                    return vertices;
                });
            }
            long count = 0;
            for (var future : workers.invokeAll(tasks))
                count += future.get();
            return count;
        }
        public void close() {
            if (workers != null)
                workers.close();
            for (var pack : packs)
                pack.close();
        }
    }
    private static long allocated(com.sun.management.ThreadMXBean bean) {
        return bean.isThreadAllocatedMemoryEnabled()
                ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
                : -1;
    }
    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans()
                .stream()
                .mapToLong(b -> Math.max(0, b.getCollectionCount()))
                .sum();
    }
    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans()
                .stream()
                .mapToLong(b -> Math.max(0, b.getCollectionTime()))
                .sum();
    }
    public void close() {
        builders.close();
    }

    /** Uses the same packed palettes as routing. HashMap-per-block lookups would bias the reference benchmark. */
    private static final class Region extends RenderSectionRegion {
        LevelChunkSection[] sections;
        int side;
        int index(int x, int y, int z) {
            return ((x + 1) * (side + 1) + z + 1) * 3 + y + 1;
        }
        SectionTintCases.World tintWorld;
        @Override
        public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) {
            return tintWorld.getBlockTint(pos, resolver);
        }
        Region() {
            super(null, 0, 0, 0, null);
        }
        public BlockState getBlockState(BlockPos p) {
            int x = p.getX() >> 4, y = p.getY() >> 4, z = p.getZ() >> 4;
            if (x < -1 || x >= side || y < -1 || y > 1 || z < -1 || z >= side)
                return Blocks.AIR.defaultBlockState();
            return sections[index(x, y, z)].getBlockState(p.getX() & 15, p.getY() & 15,
                                                          p.getZ() & 15);
        }
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p) {
            return getBlockState(p).getFluidState();
        }
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p) {
            return null;
        }
        public int getBrightness(LightLayer layer, BlockPos pos) {
            return 15;
        }
        public int getRawBrightness(BlockPos pos, int darken) {
            return 15;
        }
        public CardinalLighting cardinalLighting() {
            return new CardinalLighting(1, 1, 1, 1, 1, 1);
        }
        public int getHeight() {
            return 384;
        }
        public int getMinY() {
            return -64;
        }
    }
}
