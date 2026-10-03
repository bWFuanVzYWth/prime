package dev.primept.capture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Vector3f;

/** Actual SectionCompiler + Fabric/Indigo output. No copied tessellator, window or device.
 * Resources are controlled baked fixtures, not a claim that the entire vanilla asset pack is covered. */
final class SectionCompilerOracle {
    static void run() throws Exception {
        var type = net.minecraft.client.Minecraft.class;
        var singleton = type.getDeclaredField("instance");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        try {
            var mc = FluidRouterCpuSmoke.blank(type);
            var options = FluidRouterCpuSmoke.blank(net.minecraft.client.Options.class);
            var leaves = net.minecraft.client.Options.class.getDeclaredField("cutoutLeaves");
            leaves.setAccessible(true);
            leaves.set(options,
                       net.minecraft.client.OptionInstance.createBoolean("oracle.leaves", true));
            var field = type.getDeclaredField("options");
            field.setAccessible(true);
            field.set(mc, options);
            singleton.set(null, mc);
            fixtures();
        } finally {
            singleton.set(null, previous);
        }
    }
    private static void fixtures() throws Exception {
        var sprite = SourceSpriteFixture.create(2, 2, 12);
        var material = new Material.Baked(sprite, true);
        var full = box(material, 0, 0, 0, 1, 1, 1);
        var slab = box(material, 0, 0, 0, 1, .5f, 1);
        var empty = new SingleVariant(
                new SimpleModelWrapper(new QuadCollection.Builder().build(), false, material));
        var fluid =
                new FluidModel(ChunkSectionLayer.TRANSLUCENT, material, material, material, null);
        var lavaFluid = new FluidModel(ChunkSectionLayer.SOLID, material, material, null, null);
        var fluids = new FluidStateModelSet(
                Map.of(net.minecraft.world.level.material.Fluids.LAVA, lavaFluid,
                       net.minecraft.world.level.material.Fluids.FLOWING_LAVA, lavaFluid),
                fluid);
        var cases = new ArrayList<Case>();
        var p = new BlockPos(8, 8, 8);
        var stone = Blocks.STONE.defaultBlockState();
        var bottom = Blocks.OAK_SLAB.defaultBlockState();
        cases.add(new Case("solid", Map.of(p, stone), Map.of(stone, full)));
        cases.add(
                new Case("solid_neighbor", Map.of(p, stone, p.east(), stone), Map.of(stone, full)));
        cases.add(new Case("partial_occlusion", Map.of(p, bottom, p.east(), bottom),
                           Map.of(bottom, slab)));
        var fence = Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.NORTH, true);
        var post = box(material, .375f, 0, .375f, .625f, 1, .625f);
        var arm = box(material, .4375f, .375f, 0, .5625f, .8125f, .5f);
        var selectors = List.of(new MultiPartModel.Selector<BlockStateModel>(s -> true, post),
                                new MultiPartModel.Selector<BlockStateModel>(
                                        s -> s.getValue(BlockStateProperties.NORTH), arm),
                                new MultiPartModel.Selector<BlockStateModel>(
                                        s -> s.getValue(BlockStateProperties.SOUTH), full));
        cases.add(new Case("multipart", Map.of(p, fence),
                           Map.of(fence, multipart(fence, selectors))));
        var weighted = new WeightedVariants(
                WeightedList.<BlockStateModel>builder().add(full, 1).add(slab, 3).build());
        var weightedBlocks = new HashMap<BlockPos, BlockState>();
        for (int x = -3; x <= 3; ++x)
            weightedBlocks.put(new BlockPos(x, 8, 3), stone);
        cases.add(new Case("weighted", weightedBlocks, Map.of(stone, weighted)));
        for (var state : new BlockState[] {Blocks.SHORT_GRASS.defaultBlockState(),
                                           Blocks.POPPY.defaultBlockState(),
                                           Blocks.SMALL_DRIPLEAF.defaultBlockState(),
                                           Blocks.POINTED_DRIPSTONE.defaultBlockState()}) {
            String name =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())
                            .getPath();
            cases.add(new Case("placement_offset_" + name,
                               Map.of(p, state, p.east(3), state, new BlockPos(-8, 7, -9), state),
                               Map.of(state, full)));
        }
        for (var state : new BlockState[] {Blocks.TALL_GRASS.defaultBlockState(),
                                           Blocks.OAK_DOOR.defaultBlockState()}) {
            var upper = state.setValue(
                    BlockStateProperties.DOUBLE_BLOCK_HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
            String name =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())
                            .getPath();
            cases.add(new Case(
                    "placement_seed_" + name,
                    Map.of(p, state, p.above(), upper, p.east(3), state, p.east(3).above(), upper),
                    Map.of(state, weighted, upper, weighted)));
        }
        BlockState bed = null;
        for (var state : Block.BLOCK_STATE_REGISTRY) {
            if (SectionSources.BED_SEED_DECLARATION.isInstance(state.getBlock())) {
                bed = state;
                break;
            }
        }
        if (bed == null)
            throw new AssertionError("Missing actual bed source state");
        for (var direction :
             new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            var foot = bed.setValue(BlockStateProperties.BED_PART,
                                    net.minecraft.world.level.block.state.properties.BedPart.FOOT)
                               .setValue(BlockStateProperties.HORIZONTAL_FACING, direction);
            var head = foot.setValue(BlockStateProperties.BED_PART,
                                     net.minecraft.world.level.block.state.properties.BedPart.HEAD);
            cases.add(new Case("placement_seed_bed_" + direction.getName(),
                               Map.of(p, foot, p.relative(direction), head),
                               Map.of(foot, weighted, head, weighted)));
        }
        var multiWeighted = multipart(
                fence, List.of(new MultiPartModel.Selector<BlockStateModel>(s -> true, weighted),
                               new MultiPartModel.Selector<BlockStateModel>(s -> true, weighted)));
        cases.add(new Case("multipart_weighted", Map.of(p, fence, p.west(), fence),
                           Map.of(fence, multiWeighted)));
        var water = Blocks.WATER.defaultBlockState();
        cases.add(new Case("water", Map.of(p, water), Map.of()));
        cases.add(new Case("water_level", Map.of(p, water.setValue(BlockStateProperties.LEVEL, 4)),
                           Map.of()));
        cases.add(new Case("water_column", Map.of(p, water, p.above(), water), Map.of()));
        cases.add(new Case("water_edge",
                           Map.of(new BlockPos(15, 15, 15), water, new BlockPos(16, 16, 16), water),
                           Map.of()));
        var logged = bottom.setValue(BlockStateProperties.WATERLOGGED, true);
        cases.add(new Case("waterlogged", Map.of(p, logged), Map.of(logged, slab)));
        cases.add(new Case("lava", Map.of(p, Blocks.LAVA.defaultBlockState()), Map.of()));
        var top = bottom.setValue(BlockStateProperties.SLAB_TYPE,
                                  net.minecraft.world.level.block.state.properties.SlabType.TOP);
        cases.add(new Case("partial_opposite", Map.of(p, bottom, p.east(), top),
                           Map.of(bottom, slab, top, box(material, 0, .5f, 0, 1, 1, 1))));
        cases.add(new Case("partial_full_neighbor", Map.of(p, bottom, p.east(), stone),
                           Map.of(bottom, slab, stone, full)));
        var glass = Blocks.GLASS.defaultBlockState();
        cases.add(new Case("glass_pair", Map.of(p, glass, p.east(), glass), Map.of(glass, full)));
        iceCases(cases, material, p);
        cases.add(new Case("water_slab_side", Map.of(p, water, p.east(), bottom),
                           Map.of(bottom, slab)));
        cases.add(new Case("water_slab_below", Map.of(p, water, p.below(), bottom),
                           Map.of(bottom, slab)));
        cases.add(new Case("water_slab_above", Map.of(p, water, p.above(), bottom),
                           Map.of(bottom, slab)));
        cases.add(new Case("water_stone_side", Map.of(p, water, p.north(), stone),
                           Map.of(stone, full)));
        cases.add(
                new Case("water_overlay", Map.of(p, water, p.east(), glass), Map.of(glass, full)));
        var leavesState = Blocks.OAK_LEAVES.defaultBlockState();
        cases.add(new Case("water_leaves", Map.of(p, water, p.east(), leavesState),
                           Map.of(leavesState, full)));
        cases.add(new Case("waterlogged_top",
                           Map.of(p, top.setValue(BlockStateProperties.WATERLOGGED, true)),
                           Map.of(top.setValue(BlockStateProperties.WATERLOGGED, true),
                                  box(material, 0, .5f, 0, 1, 1, 1))));
        cases.add(new Case("waterlogged_fence",
                           Map.of(p, fence.setValue(BlockStateProperties.WATERLOGGED, true)),
                           Map.of(fence.setValue(BlockStateProperties.WATERLOGGED, true),
                                  multipart(fence, selectors))));
        for (int level = 1; level <= 15; ++level) {
            var flowing = water.setValue(BlockStateProperties.LEVEL, level);
            cases.add(new Case(
                    "water_flow_" + level,
                    Map.of(p, flowing, p.east(), water, p.west().below(), water, p.north(), stone),
                    Map.of(stone, full)));
        }
        var pool = new HashMap<BlockPos, BlockState>();
        for (int x = -1; x <= 1; ++x)
            for (int z = -1; z <= 1; ++z)
                pool.put(p.offset(x, 0, z),
                         water.setValue(BlockStateProperties.LEVEL, (x + 1) * 3 + z + 1));
        pool.put(p.north().east().above(), water);
        cases.add(new Case("water_slopes", pool, Map.of()));
        cases.add(new Case("water_negative_edge",
                           Map.of(new BlockPos(-1, -1, -1), water, new BlockPos(0, -1, -1), water,
                                  new BlockPos(0, -1, 0), water, new BlockPos(0, 0, 0), water),
                           Map.of()));
        cases.add(new Case("water_different_fluid",
                           Map.of(p, water, p.east(), Blocks.LAVA.defaultBlockState()), Map.of()));
        cases.add(new Case("empty", Map.of(), Map.of()));
        var cross = new HashMap<BlockPos, BlockState>();
        cross.put(new BlockPos(15, 15, 15), water);
        cross.put(new BlockPos(16, 15, 15), water);
        cross.put(new BlockPos(16, 15, 16), water);
        cases.add(new Case("water_cross_section", Map.copyOf(cross), Map.of()));
        cross.put(new BlockPos(16, 15, 16), water.setValue(BlockStateProperties.LEVEL, 7));
        cases.add(new Case("water_cross_section_changed", Map.copyOf(cross), Map.of()));
        var stairs = Blocks.OAK_STAIRS.defaultBlockState();
        for (var stairShape :
             net.minecraft.world.level.block.state.properties.StairsShape.values()) {
            var a = stairs.setValue(BlockStateProperties.STAIRS_SHAPE, stairShape);
            var b = a.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
            cases.add(new Case("stairs_" + stairShape.getSerializedName(),
                               Map.of(p, a, p.east(), b),
                               Map.of(a, shapeModel(material, a), b, shapeModel(material, b))));
            cases.add(new Case("water_stairs_" + stairShape.getSerializedName(),
                               Map.of(p, water, p.east(), a), Map.of(a, shapeModel(material, a))));
        }
        SectionTintCases.add(cases, material);
        if (Boolean.getBoolean("primept.section.suite"))
            SectionWorkloads.add(cases, full, fence, post, arm);
        Path directory =
                Path.of(System.getProperty("primept.smoke.routingDirectory"), "section-oracle");
        Files.createDirectories(directory);
        // A failed generation must not leave the old completion manifest usable.
        Files.deleteIfExists(directory.resolve("cases.txt"));
        for (Case fixture : cases) {
            try (var workload = new SectionWorkload(fixture, empty, fluids)) {
                workload.write(directory);
                if (Boolean.getBoolean("primept.section.bench") &&
                    fixture.name.startsWith("bench_") && !fixture.name.endsWith("_edited"))
                    workload.bench(directory);
            }
        }
        SectionTintOracle.write(directory);
        CrossUvCpuSmoke.run(directory.resolve("cross-uv"));
        Files.writeString(directory.resolve("suite.properties"),
                          "format=1\nsourceVersion=" + SourcePages.VERSION +
                                  "\ngameVersion=" + SectionSources.GAME_VERSION +
                                  "\nlighting=neutral\nresources=controlled-baked\n");
        Files.writeString(
                directory.resolve("jvm.properties"),
                "runtime=" + System.getProperty("java.runtime.version") +
                        "\nvm=" + System.getProperty("java.vm.name") +
                        "\nvmVersion=" + System.getProperty("java.vm.version") +
                        "\narchitecture=" + System.getProperty("os.arch") +
                        "\nprocessors=" + Runtime.getRuntime().availableProcessors() +
                        "\nmaxHeapBytes=" + Runtime.getRuntime().maxMemory() + "\ngc=" +
                        java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
                                .stream()
                                .map(b -> b.getName())
                                .toList() +
                        "\n");
        Files.write(directory.resolve("cases.txt"), cases.stream().map(c -> c.name).toList());
        System.out.println("PRIME_SECTION_COMPILER_ORACLE_OK: MC " + SectionSources.GAME_VERSION +
                           ", " + cases.size() +
                           " actual section compiler fixtures; neutral lighting, no GPU/window");
    }
    record Case(String name, Map<BlockPos, BlockState> blocks,
                Map<BlockState, BlockStateModel> models, int blendRadius, int biomePhase,
                int horizontalSections) {
        Case(String name, Map<BlockPos, BlockState> blocks,
             Map<BlockState, BlockStateModel> models) {
            this(name, blocks, models, -1, 0);
        }
        Case(String name, Map<BlockPos, BlockState> blocks, Map<BlockState, BlockStateModel> models,
             int blendRadius, int biomePhase) {
            this(name, blocks, models, blendRadius, biomePhase, 2);
        }
    }
    static BlockStateModel multipart(BlockState state,
                                     List<MultiPartModel.Selector<BlockStateModel>> selectors)
            throws Exception {
        var sharedType = MultiPartModel.class.getDeclaredField("shared").getType();
        var checked = new ArrayList<MultiPartModel.Selector<BlockStateModel>>();
        for (var selector : selectors) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            checked.add(new MultiPartModel.Selector<>(s -> {
                if (calls.incrementAndGet() != 1)
                    throw new AssertionError("multipart predicate replayed");
                return selector.condition().test(s);
            }, selector.model()));
        }
        var makeShared = sharedType.getDeclaredConstructor(List.class);
        makeShared.setAccessible(true);
        var makeModel = MultiPartModel.class.getDeclaredConstructor(sharedType, BlockState.class);
        makeModel.setAccessible(true);
        return (BlockStateModel)makeModel.newInstance(makeShared.newInstance(checked), state);
    }
    private static void iceCases(List<Case> cases, Material.Baked material, BlockPos p) {
        var ice = Blocks.ICE.defaultBlockState();
        var frosted = Blocks.FROSTED_ICE.defaultBlockState();
        var aged = frosted.setValue(BlockStateProperties.AGE_3, 3);
        if ((SectionResources.flags(ice) & 128) == 0 ||
            (SectionResources.flags(frosted) & 128) == 0 ||
            (SectionResources.flags(Blocks.PACKED_ICE.defaultBlockState()) & 128) != 0 ||
            (SectionResources.flags(Blocks.BLUE_ICE.defaultBlockState()) & 128) != 0)
            throw new AssertionError("Ice host class flags changed");
        var full = box(material, 0, 0, 0, 1, 1, 1, -1, ChunkSectionLayer.TRANSLUCENT, true);
        for (var direction : Direction.values()) {
            // Exercise the actual host rule, independently of the native mask helper.
            if (Block.shouldRenderFace(ice, ice, direction) ||
                Block.shouldRenderFace(frosted, aged, direction) ||
                Block.shouldRenderFace(aged, frosted, direction) ||
                !Block.shouldRenderFace(ice, frosted, direction) ||
                !Block.shouldRenderFace(frosted, ice, direction))
                throw new AssertionError("Ice exact-block face contract changed: " + direction);
            cases.add(new Case("ice_pair_" + direction.getName(),
                               Map.of(p, ice, p.relative(direction), ice), Map.of(ice, full)));
            var edge = new BlockPos(direction.getStepX() > 0 ? 15 : 0,
                                    direction.getStepY() > 0 ? 15 : 0,
                                    direction.getStepZ() > 0 ? 15 : 0);
            cases.add(new Case("ice_cross_section_" + direction.getName(),
                               Map.of(edge, ice, edge.relative(direction), ice),
                               Map.of(ice, full)));
        }
        cases.add(new Case("ice_cross_slab",
                           Map.of(new BlockPos(8, 3, 8), ice, new BlockPos(8, 4, 8), ice),
                           Map.of(ice, full)));
        cases.add(new Case("frosted_ice_age_pair", Map.of(p, frosted, p.east(), aged),
                           Map.of(frosted, full, aged, full)));
        cases.add(new Case("ice_frosted_boundary", Map.of(p, ice, p.east(), frosted),
                           Map.of(ice, full, frosted, full)));
        // A resource model can opt out of directional culling even on the known builtin.
        var unculled = box(material, 0, 0, 0, 1, 1, 1, -1, ChunkSectionLayer.TRANSLUCENT, false);
        cases.add(new Case("ice_unculled_model_pair", Map.of(p, ice, p.east(), ice),
                           Map.of(ice, unculled)));
    }
    static BlockStateModel shapeModel(Material.Baked material, BlockState state) throws Exception {
        var parts = new ArrayList<MultiPartModel.Selector<BlockStateModel>>();
        for (var a : state.getOcclusionShape().toAabbs())
            parts.add(new MultiPartModel.Selector<>(s
                                                    -> true,
                                                    box(material, (float)a.minX, (float)a.minY,
                                                        (float)a.minZ, (float)a.maxX, (float)a.maxY,
                                                        (float)a.maxZ)));
        return multipart(state, parts);
    }
    static BlockStateModel box(Material.Baked material, float x0, float y0, float z0, float x1,
                               float y1, float z1) {
        return box(material, x0, y0, z0, x1, y1, z1, -1);
    }
    static BlockStateModel box(Material.Baked material, float x0, float y0, float z0, float x1,
                               float y1, float z1, int tint) {
        return box(material, x0, y0, z0, x1, y1, z1, tint, ChunkSectionLayer.SOLID, true);
    }
    private static BlockStateModel box(Material.Baked material, float x0, float y0, float z0,
                                       float x1, float y1, float z1, int tint,
                                       ChunkSectionLayer layer, boolean culled) {
        float[][][] faces = {{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}},
                             {{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}},
                             {{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}},
                             {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}},
                             {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}},
                             {{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}}};
        var quads = new QuadCollection.Builder();
        for (var direction : Direction.values()) {
            var ps = faces[direction.ordinal()];
            var q = new BakedQuad(new Vector3f(ps[0]), new Vector3f(ps[1]), new Vector3f(ps[2]),
                                  new Vector3f(ps[3]), UVPair.pack(.125f, .125f),
                                  UVPair.pack(.875f, .125f), UVPair.pack(.875f, .875f),
                                  UVPair.pack(.125f, .875f), direction,
                                  SectionOracleMaterial.create(material.sprite(), tint, layer));
            if (culled)
                quads.addCulledFace(direction, q);
            else
                quads.addUnculledFace(q);
        }
        return new SingleVariant(new SimpleModelWrapper(quads.build(), false, material));
    }
}
