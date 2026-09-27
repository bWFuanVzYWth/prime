package dev.primept.capture;

import dev.primept.PrimeClient;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Real transformed compiler/model/tint/cull/lighting on immutable CPU regions; never a game loop. */
final class TerrainCompilerCpuSmoke {
    private record Workspace(SectionCompiler compiler, SectionBufferBuilderPack buffers,
                             SourceQuads source, Model model, long[] tintCalls)
            implements AutoCloseable {
        public void close() {
            buffers.close();
        }
    }
    private record Result(Map<Long, byte[]> packets, long[] models, long[] tints, long ns) {}

    static void run() throws Exception {
        var regions = new Region[64];
        for (int i = 0; i < regions.length; i++) {
            regions[i] = blank(Region.class);
            regions[i].dense = i < 8;
        }
        try (var workers = new SynchronousWorkers<>(8, ignored -> workspace())) {
            var baseline = compile(workers, regions, false, false);
            check(Arrays.stream(baseline.models).sum() > 0 &&
                          Arrays.stream(baseline.tints).sum() > 0,
                  "Actual model and tint callbacks");
            for (boolean balanced : new boolean[] {false, true})
                for (boolean discard : new boolean[] {false, true})
                    equivalent(baseline, compile(workers, regions, balanced, discard));
            if (Boolean.getBoolean("primept.smoke.terrainPerf")) {
                measure(workers, regions, "grouped");
                for (int i = 0; i < regions.length; i++)
                    regions[i].dense = i % 8 == 0;
                measure(workers, regions, "interleaved");
                for (var region : regions)
                    region.dense = false;
                measure(workers, regions, "uniform");
                measure(workers, Arrays.copyOf(regions, 4), "small");
            }
        } finally {
            PrimeClient.CAPTURE.reset();
        }
        System.out.println(
                "PRIME_PT_TERRAIN_COMPILER_CPU_OK: 64 heterogeneous actual compiles; serial-range/balanced workers and discarded visibility have identical source packets, model/tint counts; full join");
    }

    private static void measure(SynchronousWorkers<Workspace> workers, Region[] regions,
                                String scenario) {
        var baseline = compile(workers, regions, false, false);
        for (int round = 0; round < 24; round++)
            for (int variant = 0; variant < 4; variant++) {
                int mode = (round + variant) % 4;
                boolean balanced = (mode & 1) != 0, discard = (mode & 2) != 0;
                var result = compile(workers, regions, balanced, discard);
                equivalent(baseline, result);
                System.out.println("PRIME_PT_COMPILER_CPU_SAMPLE scenario=" + scenario +
                                   " round=" + round + " warmup=" + (round < 6) +
                                   " balanced=" + balanced + " discardVisibility=" + discard +
                                   " sections=" + regions.length + " ns=" + result.ns);
            }
    }

    private static Workspace workspace() {
        var model = new Model();
        long[] tintCalls = {0};
        var colors = new BlockColors();
        colors.register(List.of(new net.minecraft.client.color.block.BlockTintSource() {
            public int color(BlockState state) {
                return 0xff70903f;
            }
            public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
                tintCalls[0]++;
                return 0xff70903f ^ (pos.getX() & 31);
            }
        }),
                        Blocks.STONE);
        var models = new BlockStateModelSet(Map.of(Blocks.STONE.defaultBlockState(), model), model);
        return new Workspace(new SectionCompiler(true, false, models, null, colors),
                             new SectionBufferBuilderPack(), new SourceQuads(), model, tintCalls);
    }

    private static Result compile(SynchronousWorkers<Workspace> workers, Region[] regions,
                                  boolean balanced, boolean discard) {
        PrimeClient.CAPTURE.enable();
        long[] modelCounts = new long[regions.length], tintCounts = new long[regions.length];
        long startTime = System.nanoTime();
        SynchronousWorkers.Work<Workspace> work = (workspace, first, end) -> {
            for (int i = first; i < end; i++) {
                long beforeModels = workspace.model.calls, beforeTints = workspace.tintCalls[0];
                try (var output = TerrainRasterOutput.open(workspace.source)) {
                    // A different sorting identity exercises the existing full raster result path.
                    var sorting =
                            discard ? output.sorting
                                    : com.mojang.blaze3d.vertex.VertexSorting.byDistance(1, 2, 3);
                    var result = workspace.compiler.compile(SectionPos.of(i, 0, 0), regions[i],
                                                            sorting, workspace.buffers);
                    check((result.visibilitySet == null) == discard,
                          "Only private discarded visibility is absent");
                    result.release();
                    workspace.buffers.clearAll();
                }
                modelCounts[i] = workspace.model.calls - beforeModels;
                tintCounts[i] = workspace.tintCalls[0] - beforeTints;
            }
        };
        if (balanced)
            workers.runBalanced(regions.length, 1, work);
        else
            workers.run(regions.length, 8, work);
        long elapsed = System.nanoTime() - startTime;
        check(PrimeClient.CAPTURE.failure() == null, "Source capture succeeded");
        var packets = new TreeMap<Long, byte[]>();
        for (var batch : PrimeClient.CAPTURE.seal().batches()) {
            var packet = batch.packets().getFirst().clone();
            // Scheduling changes only ownership epoch and producer sequence, never source payload.
            Arrays.fill(packet, 16, 24, (byte)0);
            Arrays.fill(packet, 32, 40, (byte)0);
            packets.put(batch.section(), packet);
        }
        check(packets.size() == regions.length,
              "Every section completes before the caller resumes");
        return new Result(packets, modelCounts, tintCounts, elapsed);
    }

    private static void equivalent(Result expected, Result actual) {
        check(Arrays.equals(expected.models, actual.models) &&
                      Arrays.equals(expected.tints, actual.tints),
              "Actual model/tint callback counts are identical in every section");
        check(expected.packets.keySet().equals(actual.packets.keySet()),
              "Same actual source identities");
        for (var entry : expected.packets.entrySet())
            check(Arrays.equals(entry.getValue(), actual.packets.get(entry.getKey())),
                  "Bit-identical geometry/material/visibility/tint source bytes");
    }

    private static final class Model implements BlockStateModel {
        private long calls;
        @Override
        public void emitQuads(QuadEmitter output, BlockAndTintGetter level, BlockPos pos,
                              BlockState state, RandomSource random, Predicate<Direction> cull) {
            ++calls;
            int color = 0xffd0e0f0 ^ (random.nextInt() & 15);
            for (Direction direction : Direction.values()) {
                // Exercise both model-side and actual Indigo culling, without repeating callbacks.
                if (direction == Direction.DOWN && cull.test(direction))
                    continue;
                output.square(direction, 0, 0, 1, 1, 0)
                        .cullFace(direction)
                        .chunkLayer((pos.getX() & 1) == 0 ? ChunkSectionLayer.SOLID
                                                          : ChunkSectionLayer.CUTOUT)
                        .tintIndex(0);
                for (int v = 0; v < 4; v++)
                    output.uv(v, v & 1, v >> 1).color(v, color);
                output.emit();
            }
        }
        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
            throw new AssertionError("Actual pinned Fabric source path required");
        }
        @Override
        public Material.Baked particleMaterial() {
            return null;
        }
        @Override
        public int materialFlags() {
            return 0;
        }
    }

    private static final class Region extends RenderSectionRegion {
        boolean dense;
        private Region() {
            super(null, 0, 0, 0, null);
        }
        @Override
        public BlockState getBlockState(BlockPos pos) {
            int x = pos.getX() & 15, y = pos.getY(), z = pos.getZ() & 15;
            boolean stone = dense ? y >= 0 && y < 16 && ((x + y + z) & 1) == 0 : y == 0;
            return (stone ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        }
        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            return 12;
        }
        @Override
        public int getRawBrightness(BlockPos pos, int darken) {
            return 12 - darken;
        }
        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }
        @Override
        public int getHeight() {
            return 384;
        }
        @Override
        public int getMinY() {
            return -64;
        }
    }

    private static <T> T blank(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(
                unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }
    private static void check(boolean pass, String message) {
        if (!pass)
            throw new AssertionError(message);
    }
}
