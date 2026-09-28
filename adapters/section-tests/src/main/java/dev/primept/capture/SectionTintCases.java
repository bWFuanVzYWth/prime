package dev.primept.capture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Real vanilla tint sources, colormaps, biome zoom, cache and blend; controlled biome palette. */
final class SectionTintCases {
    static BlockColors colors() {
        var colors = BlockColors.createDefault();
        colors.register(List.of(BlockTintSources.constant(0x8070903f),
                                BlockTintSources.constant(0x20345678)),
                        Blocks.DIAMOND_BLOCK);
        return colors;
    }
    static void add(List<SectionCompilerOracle.Case> cases, Material.Baked material)
            throws Exception {
        for (var name : List.of("grass", "foliage", "dry_foliage")) {
            try (var input = Minecraft.class.getResourceAsStream(
                         "/assets/minecraft/textures/colormap/" + name + ".png")) {
                if (input == null)
                    throw new AssertionError("Missing vanilla colormap: " + name);
                var png = ImageIO.read(input);
                var pixels =
                        png.getRGB(0, 0, png.getWidth(), png.getHeight(), null, 0, png.getWidth());
                switch (name) {
                case "grass" -> net.minecraft.world.level.GrassColor.init(pixels);
                case "foliage" -> net.minecraft.world.level.FoliageColor.init(pixels);
                case "dry_foliage" -> net.minecraft.world.level.DryFoliageColor.init(pixels);
                }
            }
        }
        var tinted = SectionCompilerOracle.box(material, 0, 0, 0, 1, 1, 1, 0);
        var slot1 = SectionCompilerOracle.box(material, 0, 0, 0, 1, 1, 1, 1);
        var blocks = new HashMap<BlockPos, BlockState>();
        var models = new HashMap<BlockState, BlockStateModel>();
        for (int power = 0; power < 16; ++power) {
            var state = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(
                    BlockStateProperties.POWER, power);
            blocks.put(new BlockPos(power * 2 - 1, 8, -1), state);
            models.put(state, tinted);
        }
        cases.add(new SectionCompilerOracle.Case("tint_redstone_power", Map.copyOf(blocks),
                                                 Map.copyOf(models)));
        blocks.clear();
        models.clear();
        for (int age = 0; age < 8; ++age) {
            var state =
                    Blocks.MELON_STEM.defaultBlockState().setValue(BlockStateProperties.AGE_7, age);
            blocks.put(new BlockPos(age * 2, 8, 3), state);
            models.put(state, tinted);
        }
        cases.add(new SectionCompilerOracle.Case("tint_stem_age", Map.copyOf(blocks),
                                                 Map.copyOf(models)));
        for (int radius : new int[] {0, 2, 7}) {
            blocks.clear();
            models.clear();
            var types = List.of(Blocks.GRASS_BLOCK, Blocks.OAK_LEAVES, Blocks.BIRCH_LEAVES,
                                Blocks.SPRUCE_LEAVES, Blocks.LEAF_LITTER, Blocks.PINK_PETALS,
                                Blocks.WATER_CAULDRON, Blocks.SUGAR_CANE, Blocks.LILY_PAD,
                                Blocks.TALL_GRASS, Blocks.MANGROVE_LEAVES, Blocks.VINE);
            for (int i = 0; i < types.size(); ++i) {
                var state = types.get(i).defaultBlockState();
                blocks.put(new BlockPos((i % 6) * 5 - 1, i < 6 ? 4 : 17, -1), state);
                models.put(state, i == 5 ? slot1 : tinted);
                if (types.get(i) == Blocks.TALL_GRASS) {
                    var upper = state.setValue(
                            BlockStateProperties.DOUBLE_BLOCK_HALF,
                            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                    blocks.put(new BlockPos((i % 6) * 5 - 1, 18, -1), upper);
                    models.put(upper, tinted);
                }
            }
            blocks.put(new BlockPos(15, 15, 15), Blocks.WATER.defaultBlockState());
            blocks.put(new BlockPos(16, 15, 15), Blocks.OAK_SLAB.defaultBlockState().setValue(
                                                         BlockStateProperties.WATERLOGGED, true));
            models.put(Blocks.OAK_SLAB.defaultBlockState().setValue(
                               BlockStateProperties.WATERLOGGED, true),
                       SectionCompilerOracle.box(material, 0, 0, 0, 1, .5f, 1));
            for (int phase : new int[] {0, 1})
                cases.add(new SectionCompilerOracle.Case("tint_biomes_" + radius + "_" + phase,
                                                         Map.copyOf(blocks), Map.copyOf(models),
                                                         radius, phase));
        }
        var diamond = Blocks.DIAMOND_BLOCK.defaultBlockState();
        var mixed = SectionCompilerOracle.multipart(
                diamond,
                List.of(new net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel
                                .Selector<BlockStateModel>(s -> true, tinted),
                        new net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel
                                .Selector<BlockStateModel>(s -> true, slot1),
                        new net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel
                                .Selector<BlockStateModel>(
                                        s
                                        -> true,
                                        SectionCompilerOracle.box(material, 0, 0, 0, 1, 1, 1, 5))));
        cases.add(new SectionCompilerOracle.Case("tint_slots_alpha_missing",
                                                 Map.of(new BlockPos(8, 8, 8), diamond),
                                                 Map.of(diamond, mixed)));
        // Configurable dense workset; only exposed surfaces need source color evaluation.
        int side = Integer.getInteger("primept.section.tintSide", 2);
        if (side < 2 || side > 16)
            throw new IllegalArgumentException("Tint section side must be in [2,16]");
        blocks.clear();
        models.clear();
        var grass = Blocks.GRASS_BLOCK.defaultBlockState();
        models.put(grass, tinted);
        for (int y = 0; y < 32; ++y)
            for (int z = 0; z < side * 16; ++z)
                for (int x = 0; x < side * 16; ++x)
                    blocks.put(new BlockPos(x, y, z), grass);
        cases.add(new SectionCompilerOracle.Case("bench_tinted", SectionWorkloads.snapshot(blocks),
                                                 Map.copyOf(models), 7, 0, side));
        cases.add(new SectionCompilerOracle.Case("tint_dense_biome_changed",
                                                 SectionWorkloads.snapshot(blocks),
                                                 Map.copyOf(models), 7, 1, side));
        for (int x = 0; x < side; ++x)
            for (int y = 0; y < 2; ++y)
                for (int z = 0; z < side; ++z)
                    blocks.remove(new BlockPos(x * 16 + 8, y * 16 + 8, z * 16 + 8));
        cases.add(new SectionCompilerOracle.Case("bench_tinted_edited",
                                                 SectionWorkloads.snapshot(blocks),
                                                 Map.copyOf(models), 7, 0, side));
    }
    static World world(int radius, int phase) throws Exception {
        var option = Minecraft.getInstance().options;
        var field = option.getClass().getDeclaredField("biomeBlendRadius");
        field.setAccessible(true);
        field.set(option,
                  new net.minecraft.client.OptionInstance<Integer>(
                          "oracle.blend", net.minecraft.client.OptionInstance.noTooltip(),
                          (c, v)
                                  -> net.minecraft.network.chat.Component.literal(v.toString()),
                          new net.minecraft.client.OptionInstance.IntRange(0, 7),
                          Math.max(radius, 0), v -> {}));
        var world = FluidRouterCpuSmoke.blank(World.class);
        world.phase = phase;
        var biomes = List.of(
                biome(.8f, .4f, 0x3f76e4, BiomeSpecialEffects.GrassColorModifier.NONE),
                biome(.8f, .9f, 0x617b64, BiomeSpecialEffects.GrassColorModifier.SWAMP),
                biome(.2f, .3f, 0x3938c9, BiomeSpecialEffects.GrassColorModifier.DARK_FOREST));
        world.biomes = new BiomeManager(
                (x, y, z)
                        -> biomes.get(Math.floorMod(Math.floorDiv(x + world.phase * 4, 4) + y +
                                                            Math.floorDiv(z, 4),
                                                    3)),
                BiomeManager.obfuscateSeed(123456789L));
        world.caches = new java.util.IdentityHashMap<>();
        for (var resolver :
             List.of(net.minecraft.client.renderer.BiomeColors.GRASS_COLOR_RESOLVER,
                     net.minecraft.client.renderer.BiomeColors.FOLIAGE_COLOR_RESOLVER,
                     net.minecraft.client.renderer.BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER,
                     net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER))
            world.caches.put(resolver,
                             new BlockTintCache(p -> world.calculateBlockTint(p, resolver)));
        return world;
    }
    private static Holder<Biome> biome(float temperature, float downfall, int water,
                                       BiomeSpecialEffects.GrassColorModifier modifier) {
        return Holder.direct(new Biome.BiomeBuilder()
                                     .temperature(temperature)
                                     .downfall(downfall)
                                     .specialEffects(new BiomeSpecialEffects.Builder()
                                                             .waterColor(water)
                                                             .grassColorModifier(modifier)
                                                             .build())
                                     .mobSpawnSettings(MobSpawnSettings.EMPTY)
                                     .generationSettings(BiomeGenerationSettings.EMPTY)
                                     .build());
    }
    static final class World extends ClientLevel {
        BiomeManager biomes;
        int phase;
        Map<ColorResolver, BlockTintCache> caches;
        private World() {
            super(null, null, null, null, 0, 0, null, false, 0, 0);
        }
        @Override
        public Holder<Biome> getBiome(BlockPos pos) {
            return biomes.getBiome(pos);
        }
        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return caches.get(resolver).getColor(pos);
        }
        void clearColors() {
            caches.values().forEach(BlockTintCache::invalidateAll);
        }
    }
}
