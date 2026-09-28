package dev.primept.capture;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Bind source identity/fields and evaluate requested host results; no section scan or blend loop. */
final class SectionTints {
    private static final ValueLayout.OfInt I =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong L =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    // Exact vanilla classes, never inferred by block name or speculative callback execution.
    private static final Map<Class<?>, Integer> KINDS = Map.of(
            BlockTintSources.grass().getClass(), 1, BlockTintSources.grassBlock().getClass(), 1,
            BlockTintSources.sugarCane().getClass(), 1, BlockTintSources.foliage().getClass(), 2,
            BlockTintSources.dryFoliage().getClass(), 3, BlockTintSources.water().getClass(), 4,
            BlockTintSources.doubleTallGrass().getClass(), 5);
    private static final ColorResolver[] RESOLVERS = {
            null, BiomeColors.GRASS_COLOR_RESOLVER, BiomeColors.FOLIAGE_COLOR_RESOLVER,
            BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER, BiomeColors.WATER_COLOR_RESOLVER};
    static void respond(MemorySegment request, SourcePages response, BlockAndTintGetter level,
                        BlockColors blocks, FluidStateModelSet fluids, ClientLevel biomeWorld) {
        long count = request.get(L, 8);
        int phase = request.get(I, 28), stride = phase == 0 ? 20 : 16;
        if (phase < 0 || phase > 1 ||
            request.byteSize() != Math.addExact(32, Math.multiplyExact(count, stride)) ||
            request.get(I, 24) != SectionSources.GAME_VERSION)
            throw new IllegalArgumentException("Invalid color request layout");
        response.header(SectionSources.GAME_VERSION, phase == 0 ? 3 : 4, request.get(L, 16),
                        request.get(L, 0))
                .l(count);
        if (phase == 0)
            response.i(Minecraft.getInstance().options.biomeBlendRadius().get());
        var pos = new BlockPos.MutableBlockPos();
        for (long n = 0, at = 32; n < count; ++n, at += stride) {
            pos.set(request.get(I, at), request.get(I, at + 4), request.get(I, at + 8));
            if (phase == 1) {
                int resolver = request.get(I, at + 12);
                if (resolver < 1 || resolver >= RESOLVERS.length)
                    throw new IllegalArgumentException("Unknown biome resolver");
                response.i(RESOLVERS[resolver].getColor(biomeWorld.getBiome(pos).value(),
                                                        pos.getX(), pos.getZ()));
                continue;
            }
            var state = Block.stateById(request.get(I, at + 12));
            int slot = request.get(I, at + 16);
            var source = slot == -1 ? fluids.get(state.getFluidState()).tintSource()
                                    : blocks.getTintSource(state, slot);
            int kind = source == null ? 0 : KINDS.getOrDefault(source.getClass(), 0);
            response.i(kind).i(
                    kind == 0 ? (source == null ? -1 : source.colorInWorld(state, level, pos))
                    : kind == 5 && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) ==
                                            DoubleBlockHalf.UPPER
                            ? 1
                            : 0);
        }
    }
}
