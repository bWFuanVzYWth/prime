package dev.primept.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** The real host tessellator is a test oracle only. Production routing cannot call it. */
final class FluidRouterCpuSmoke {
    static void run() throws Exception {
        // This preLaunch fixture has no resource pack reload. Bind an explicit empty tag set
        // for its synthetic world; the native and real Java oracle use the same source state.
        var bindTags = net.minecraft.core.Holder.Reference.class.getDeclaredMethod(
                "bindTags", java.util.Collection.class);
        bindTags.setAccessible(true);
        for (var registry :
             java.util.List.of(net.minecraft.core.registries.BuiltInRegistries.BLOCK,
                               net.minecraft.core.registries.BuiltInRegistries.FLUID))
            for (var holder : registry.listElements().toList())
                bindTags.invoke(holder, java.util.Set.of());
        var sprite = blank(TextureAtlasSprite.class);
        for (var name : new String[] {"u0", "v0", "u1", "v1"}) {
            var field = TextureAtlasSprite.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setFloat(sprite, name.endsWith("0") ? .125f : .875f);
        }
        var material = new Material.Baked(sprite, true);
        var model = new FluidModel(ChunkSectionLayer.TRANSLUCENT, material, material, material,
                                   new BlockTintSource() {
                                       public int color(BlockState state) {
                                           return 0x8070903f;
                                       }
                                   });
        var models = new FluidStateModelSet(Map.of(), model);
        var pos = new BlockPos(8, 7, 8);
        for (int scenario = 0; scenario < 8; ++scenario) {
            var region = blank(Region.class);
            region.blocks = new HashMap<>();
            var water = Blocks.WATER.defaultBlockState();
            region.blocks.put(pos, water);
            switch (scenario) {
            case 1 -> region.blocks.put(pos.east(), Blocks.STONE.defaultBlockState());
            case 2 -> region.blocks.put(pos.west(), Blocks.OAK_SLAB.defaultBlockState());
            case 3 -> region.blocks.put(pos.above(), Blocks.STONE.defaultBlockState());
            case 4 -> region.blocks.put(pos.below(), Blocks.STONE.defaultBlockState());
            case 5 -> {
                region.blocks.put(pos.above(), water);
                region.blocks.put(pos.north(), water);
            }
            case 6 -> {
                region.blocks.put(pos.north(), Blocks.GLASS.defaultBlockState());
                region.blocks.put(pos.south(), water);
            }
            case 7 -> {
                water = Blocks.OAK_SLAB.defaultBlockState().setValue(
                        BlockStateProperties.WATERLOGGED, true);
                region.blocks.put(pos, water);
            }
            }
            var data = new RouteBuffer();
            region.routing = true;
            TerrainRouterCpuSmoke.check(
                    FluidRouter.write(data, models, region, pos, water, water.getFluidState()),
                    "Fluid source emitted");
            var source = new LegacyTerrainInbox(true);
            var token = source.begin(SectionPos.of(0, 0, 0));
            source.route(token, new RouteBuffer()
                                        .header(12, 1)
                                        .l(token.section())
                                        .l(token.revision())
                                        .d(0)
                                        .d(0)
                                        .d(0)
                                        .i(0)
                                        .i(1)
                                        .append(data)
                                        .seal());
            source.complete(token);
            region.routing = false;
            var quads = new SourceQuads();
            VertexConsumer sink = (VertexConsumer)Proxy.newProxyInstance(
                    FluidRouterCpuSmoke.class.getClassLoader(),
                    new Class<?>[] {VertexConsumer.class}, (proxy, method, args) -> {
                        if (method.getName().equals("addVertex") && args.length == 11)
                            quads.vertex(2, (float)args[0], (float)args[1], (float)args[2],
                                         (int)args[3], (float)args[4], (float)args[5]);
                        return method.getReturnType() == void.class ? null : proxy;
                    });
            new FluidRenderer(models).tesselate(region, pos,
                                                layer -> sink, water, water.getFluidState());
            var expected = new LegacyTerrainInbox(true);
            expected.capture(expected.begin(SectionPos.of(0, 0, 0)), quads);
            TerrainRouterCpuSmoke.check(expected.failure() == null, "Oracle complete primitives");
            TerrainRouterCpuSmoke.write("fluid-" + scenario, source.seal(), expected.seal());
        }
    }
    private static final class Region extends RenderSectionRegion {
        Map<BlockPos, BlockState> blocks;
        boolean routing;
        Region() {
            super(null, 0, 0, 0, null);
        }
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        public int getBrightness(LightLayer layer, BlockPos pos) {
            if (routing)
                throw new AssertionError("Router queried raster light");
            return 12;
        }
        public int getRawBrightness(BlockPos pos, int darken) {
            return 12;
        }
        public CardinalLighting cardinalLighting() {
            if (routing)
                throw new AssertionError("Router queried raster shading");
            return new CardinalLighting(1, 1, 1, 1, 1, 1);
        }
        public int getHeight() {
            return 384;
        }
        public int getMinY() {
            return -64;
        }
    }
    static <T> T blank(Class<T> type) throws Exception {
        var unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(
                unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }
}
