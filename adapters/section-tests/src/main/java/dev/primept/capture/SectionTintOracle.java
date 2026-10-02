package dev.primept.capture;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.Arena;
import dev.primept.abi.PrimeAbi.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Independent actual-game math and stateful callback oracles, outside benchmark intervals. */
final class SectionTintOracle {
    static void write(Path directory) throws Exception {
        sources(directory);
        var grass = (int[])SectionBiomes.get(
                SectionBiomes.field(net.minecraft.world.level.GrassColor.class, "pixels"), null);
        var foliage = (int[])SectionBiomes.get(
                SectionBiomes.field(net.minecraft.world.level.FoliageColor.class, "pixels"), null);
        var dry = (int[])SectionBiomes.get(
                SectionBiomes.field(net.minecraft.world.level.DryFoliageColor.class, "pixels"),
                null);
        try {
            for (int variant = 0; variant < 2; ++variant) {
                if (variant == 1) {
                    int[] pixels = new int[17];
                    for (int i = 0; i < pixels.length; ++i)
                        pixels[i] = 0x20347600 + i;
                    net.minecraft.world.level.GrassColor.init(pixels);
                    net.minecraft.world.level.FoliageColor.init(pixels);
                    net.minecraft.world.level.DryFoliageColor.init(pixels);
                }
                math(directory.resolve("biome-math-" + variant + ".bin"));
            }
        } finally {
            net.minecraft.world.level.GrassColor.init(grass);
            net.minecraft.world.level.FoliageColor.init(foliage);
            net.minecraft.world.level.DryFoliageColor.init(dry);
        }
    }
    private static Biome biome(float t, float h, BiomeSpecialEffects.GrassColorModifier modifier,
                               boolean overrides) {
        var effects = new BiomeSpecialEffects.Builder()
                              .waterColor(0x407abcde)
                              .grassColorModifier(modifier);
        if (overrides)
            effects.grassColorOverride(0x80983412)
                    .foliageColorOverride(0x30123456)
                    .dryFoliageColorOverride(0x40abcdef);
        return new Biome.BiomeBuilder()
                .temperature(t)
                .downfall(h)
                .specialEffects(effects.build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY)
                .generationSettings(BiomeGenerationSettings.EMPTY)
                .build();
    }
    private static void math(Path file) throws Exception {
        var biomes = new ArrayList<Biome>();
        for (var modifier : BiomeSpecialEffects.GrassColorModifier.values())
            for (boolean overrides : new boolean[] {false, true})
                for (float[] climate :
                     new float[][] {{-.4f, .8f}, {.2f, .3f}, {.8f, .9f}, {1f, 1f}, {2f, 1.1f}})
                    biomes.add(biome(climate[0], climate[1], modifier, overrides));
        var holder = Holder.direct(biomes.getFirst());
        var chosen = new BlockPos.MutableBlockPos();
        var pos = new BlockPos.MutableBlockPos();
        var random = new Random(0x6ab739de);
        try (var out = new SourcePages(); var typed = new McSourceBatch()) {
            out.header(SectionSources.GAME_VERSION, 90, 1, 1);
            SectionBiomes.definitions(typed, new BiomeManager((x, y, z) -> holder, 0));
            SourceFixtureWire.definitions(typed, out);
            out.i(65536);
            for (long seed : new long[] {0, -1, Long.MIN_VALUE, 0x123456789abcdefL}) {
                var manager = new BiomeManager((x, y, z) -> {
                    chosen.set(x, y, z);
                    return holder;
                }, seed);
                for (int i = 0; i < 16384; ++i) {
                    if (i < 8192)
                        pos.set((i & 63) - 32, (i >>> 12) - 1, ((i >>> 6) & 63) - 32);
                    else
                        pos.set(random.nextInt(60000001) - 30000000, random.nextInt(4096) - 2048,
                                random.nextInt(60000001) - 30000000);
                    manager.getBiome(pos);
                    out.l(seed)
                            .i(pos.getX())
                            .i(pos.getY())
                            .i(pos.getZ())
                            .i(chosen.getX())
                            .i(chosen.getY())
                            .i(chosen.getZ());
                }
            }
            out.i(biomes.size());
            for (var biome : biomes) {
                typed.biomes.clear();
                SectionBiomes.fields(typed, biome);
                SourceFixtureWire.biome(typed.biomes.get(0), out);
                out.i(8192);
                for (int i = 0; i < 8192; ++i) {
                    int x = i < 4096 ? (i & 63) - 32 : random.nextInt(60000001) - 30000000;
                    int z = i < 4096 ? (i >>> 6) - 32 : random.nextInt(60000001) - 30000000;
                    out.i(x).i(z)
                            .i(biome.getGrassColor(x, z))
                            .i(biome.getFoliageColor())
                            .i(biome.getDryFoliageColor())
                            .i(biome.getWaterColor());
                }
            }
            SectionSourcesCpuSmoke.write(out, file);
        }
    }
    private static void sources(Path directory) throws Exception {
        var colors = SectionTintCases.colors();
        var world = SectionTintCases.world(0, 0);
        var states = new ArrayList<BlockState>();
        var slots = new ArrayList<Integer>();
        var expected = new ArrayList<Integer>();
        var pos = new BlockPos(8, 8, 8);
        for (int power = 0; power < 16; ++power) {
            var state = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(
                    BlockStateProperties.POWER, power);
            states.add(state);
            slots.add(0);
            expected.add(colors.getTintSource(state, 0).colorInWorld(state, world, pos));
        }
        for (int age = 0; age < 8; ++age) {
            var state =
                    Blocks.MELON_STEM.defaultBlockState().setValue(BlockStateProperties.AGE_7, age);
            states.add(state);
            slots.add(0);
            expected.add(colors.getTintSource(state, 0).colorInWorld(state, world, pos));
        }
        int[] calls = {0};
        BlockTintSource custom = state -> {
            ++calls[0];
            return 0x50123456;
        };
        var sources = List.of(BlockTintSources.constant(0x20769412),
                              BlockTintSources.constant(0x8070ffff, 0x40abcdef),
                              BlockTintSources.waterParticles(), custom);
        colors.register(sources, Blocks.DIAMOND_BLOCK);
        var diamond = Blocks.DIAMOND_BLOCK.defaultBlockState();
        for (int i = 0; i < sources.size(); ++i) {
            states.add(diamond);
            slots.add(i);
            expected.add(i == 3 ? 0x50123456 : sources.get(i).colorInWorld(diamond, world, pos));
        }
        states.add(Blocks.STONE.defaultBlockState());
        slots.add(99);
        expected.add(-1);
        try (var arena = Arena.ofConfined(); var typed = new McSourceBatch();
             var out = new SourcePages()) {
            var request = arena.allocate(PrimeMcRequests.LAYOUT);
            request.fill((byte)0);
            McSourceBatch.identity(PrimeMcRequests.identity(request), PrimeMcRequests.SIZE,
                                   SectionSources.GAME_VERSION, 1, 1, 1);
            PrimeMcRequests.phase(request, 2);
            PrimeMcRequests.color_count(request, states.size());
            var requests =
                    arena.allocate(Math.multiplyExact(states.size(), PrimeMcColorRequest.SIZE),
                                   PrimeMcColorRequest.ALIGN);
            requests.fill((byte)0);
            PrimeMcRequests.colors(request, requests);
            for (int i = 0; i < states.size(); i++) {
                var item = requests.asSlice(i * PrimeMcColorRequest.SIZE, PrimeMcColorRequest.SIZE);
                PrimeMcColorRequest.x(item, 8);
                PrimeMcColorRequest.y(item, 8);
                PrimeMcColorRequest.z(item, 8);
                PrimeMcColorRequest.state(item, Block.getId(states.get(i)));
                PrimeMcColorRequest.slot(item, slots.get(i));
            }
            world.rejectColorCallbacks = true;
            var result = SectionTints.respond(request, typed, world, colors, null, world);
            SourceFixtureWire.colors(typed, result, false, out, 0);
            if (calls[0] != 1)
                throw new AssertionError("Custom tint callback must run exactly once, got " +
                                         calls[0]);
            SectionSourcesCpuSmoke.write(out, directory.resolve("tint-sources.bin"));
            out.clear();
            out.i(expected.size());
            for (int value : expected)
                out.i(value);
            SectionSourcesCpuSmoke.write(out, directory.resolve("tint-sources.expected"));
        }
    }
}
