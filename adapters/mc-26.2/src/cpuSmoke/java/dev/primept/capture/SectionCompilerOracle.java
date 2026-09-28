package dev.primept.capture;

import com.mojang.blaze3d.vertex.VertexSorting;
import java.io.DataOutputStream;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
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
        var sprite = FluidRouterCpuSmoke.blank(TextureAtlasSprite.class);
        for (String name : List.of("u0", "v0", "u1", "v1")) {
            var field = TextureAtlasSprite.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setFloat(sprite, name.endsWith("0") ? .125f : .875f);
        }
        var contents = TextureAtlasSprite.class.getDeclaredField("contents");
        contents.setAccessible(true);
        contents.set(sprite, FluidRouterCpuSmoke.blank(
                                     net.minecraft.client.renderer.texture.SpriteContents.class));
        var atlas = TextureAtlasSprite.class.getDeclaredField("atlasLocation");
        atlas.setAccessible(true);
        atlas.set(sprite, net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
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
        Path directory =
                Path.of(System.getProperty("primept.smoke.routingDirectory"), "section-oracle");
        Files.createDirectories(directory);
        for (Case fixture : cases) {
            for (var pos : fixture.blocks.keySet())
                if (pos.getX() < -16 || pos.getX() > 31 || pos.getY() < -16 || pos.getY() > 31 ||
                    pos.getZ() < -16 || pos.getZ() > 31)
                    throw new AssertionError("fixture outside closed source volume");
            var models = new BlockStateModelSet(fixture.models, empty);
            var region = FluidRouterCpuSmoke.blank(Region.class);
            region.blocks = fixture.blocks;
            var expected = new CaptureInbox(true);
            var quads = new SourceQuads();
            try (var builders = new SectionBufferBuilderPack(); var source = new SourcePages()) {
                var router = new SectionSources(models, fluids);
                source.header(SectionSources.GAME_VERSION, 2, 1, 1);
                var compiler = new SectionCompiler(false, true, models, fluids, new BlockColors());
                for (int sx = -1; sx <= 1; ++sx)
                    for (int sz = -1; sz <= 1; ++sz)
                        for (int sy = -1; sy <= 1; ++sy) {
                            var section =
                                    SectionSourcesCpuSmoke.section(Blocks.AIR.defaultBlockState());
                            for (var entry : fixture.blocks.entrySet()) {
                                var pos = entry.getKey();
                                if ((pos.getX() >> 4) == sx && (pos.getY() >> 4) == sy &&
                                    (pos.getZ() >> 4) == sz)
                                    section.getStates().set(pos.getX() & 15, pos.getY() & 15,
                                                            pos.getZ() & 15, entry.getValue());
                            }
                            // Native source is prepared before the oracle can warm the model's lazy caches.
                            router.section(source, sx, sy, sz, section);
                            var result =
                                    compiler.compile(SectionPos.of(sx, sy, sz), region,
                                                     VertexSorting.byDistance(0, 0, 0), builders);
                            try {
                                for (var entry : result.renderedLayers.entrySet()) {
                                    var mesh = entry.getValue();
                                    var format = mesh.drawState().format();
                                    var bytes = mesh.vertexBuffer().order(ByteOrder.LITTLE_ENDIAN);
                                    int stride = format.getVertexSize(),
                                        position = format.getElement("Position").offset(),
                                        color = format.getElement("Color").offset(),
                                        uv = format.getElement("UV0").offset();
                                    for (int v = 0; v < mesh.drawState().vertexCount(); ++v) {
                                        int at = bytes.position() + v * stride;
                                        int rgba = bytes.getInt(at + color);
                                        int argb = (rgba & 0xff00ff00) | ((rgba & 255) << 16) |
                                                   ((rgba >>> 16) & 255);
                                        quads.vertex(entry.getKey().ordinal(),
                                                     sx * 16 + bytes.getFloat(at + position),
                                                     sy * 16 + bytes.getFloat(at + position + 4),
                                                     sz * 16 + bytes.getFloat(at + position + 8),
                                                     argb, bytes.getFloat(at + uv),
                                                     bytes.getFloat(at + uv + 4));
                                    }
                                }
                            } finally {
                                result.release();
                                builders.clearAll();
                            }
                        }
                source.i(0);
                SectionSourcesCpuSmoke.write(source, directory.resolve(fixture.name + ".source"));
            }
            expected.capture(expected.begin(SectionPos.of(0, 0, 0)), quads);
            var packets =
                    expected.seal().batches().stream().flatMap(b -> b.packets().stream()).toList();
            try (var out = new DataOutputStream(
                         Files.newOutputStream(directory.resolve(fixture.name + ".expected")))) {
                out.writeInt(packets.size());
                for (byte[] packet : packets) {
                    out.writeInt(packet.length);
                    out.write(packet);
                }
            }
        }
        Files.write(directory.resolve("cases.txt"), cases.stream().map(c -> c.name).toList());
        System.out.println("PRIME_SECTION_COMPILER_ORACLE_OK: MC " + SectionSources.GAME_VERSION +
                           ", " + cases.size() +
                           " actual section compiler fixtures; neutral lighting, no GPU/window");
    }
    private record Case(String name, Map<BlockPos, BlockState> blocks,
                        Map<BlockState, BlockStateModel> models) {}
    private static BlockStateModel
    multipart(BlockState state, List<MultiPartModel.Selector<BlockStateModel>> selectors)
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
    private static BlockStateModel shapeModel(Material.Baked material, BlockState state)
            throws Exception {
        var parts = new ArrayList<MultiPartModel.Selector<BlockStateModel>>();
        for (var a : state.getOcclusionShape().toAabbs())
            parts.add(new MultiPartModel.Selector<>(s
                                                    -> true,
                                                    box(material, (float)a.minX, (float)a.minY,
                                                        (float)a.minZ, (float)a.maxX, (float)a.maxY,
                                                        (float)a.maxZ)));
        return multipart(state, parts);
    }
    private static BlockStateModel box(Material.Baked material, float x0, float y0, float z0,
                                       float x1, float y1, float z1) {
        float[][][] faces = {{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}},
                             {{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}},
                             {{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}},
                             {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}},
                             {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}},
                             {{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}}};
        var quads = new QuadCollection.Builder();
        for (var direction : Direction.values()) {
            var ps = faces[direction.ordinal()];
            var q = new BakedQuad(
                    new Vector3f(ps[0]), new Vector3f(ps[1]), new Vector3f(ps[2]),
                    new Vector3f(ps[3]), UVPair.pack(.125f, .125f), UVPair.pack(.875f, .125f),
                    UVPair.pack(.875f, .875f), UVPair.pack(.125f, .875f), direction,
                    new BakedQuad.MaterialInfo(
                            material.sprite(), ChunkSectionLayer.SOLID,
                            net.minecraft.client.renderer.Sheets.cutoutBlockItemSheet(), -1, false,
                            0));
            quads.addCulledFace(direction, q);
        }
        return new SingleVariant(new SimpleModelWrapper(quads.build(), false, material));
    }
    private static final class Region extends RenderSectionRegion {
        Map<BlockPos, BlockState> blocks;
        Region() {
            super(null, 0, 0, 0, null);
        }
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
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
