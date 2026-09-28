package dev.primept.capture;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Bind known source fields; only unknown sources require actual color callbacks. */
final class SectionTints {
    private static final ValueLayout.OfInt I =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong L =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final Class<?> CONSTANT = BlockTintSources.constant(0).getClass();
    private static final Class<?> CONSTANT_PAIR = BlockTintSources.constant(0, 0).getClass();
    private static final Map<Class<?>, Field> CONSTANTS =
            Map.of(CONSTANT, SectionBiomes.field(CONSTANT, "arg$1"), CONSTANT_PAIR,
                   SectionBiomes.field(CONSTANT_PAIR, "val$colorInWorld"));
    // Exact factory classes, never guessed from block names or speculative callback execution.
    private static final Map<Class<?>, Integer> KINDS =
            Map.ofEntries(Map.entry(BlockTintSources.grass().getClass(), 1),
                          Map.entry(BlockTintSources.grassBlock().getClass(), 1),
                          Map.entry(BlockTintSources.sugarCane().getClass(), 1),
                          Map.entry(BlockTintSources.foliage().getClass(), 2),
                          Map.entry(BlockTintSources.dryFoliage().getClass(), 3),
                          Map.entry(BlockTintSources.water().getClass(), 4),
                          Map.entry(BlockTintSources.doubleTallGrass().getClass(), 5),
                          Map.entry(BlockTintSources.redstone().getClass(), 6),
                          Map.entry(BlockTintSources.stem().getClass(), 7), Map.entry(CONSTANT, 8),
                          Map.entry(CONSTANT_PAIR, 8),
                          Map.entry(BlockTintSources.waterParticles().getClass(), 8));
    static void respond(MemorySegment request, SourcePages response, BlockAndTintGetter level,
                        BlockColors blocks, FluidStateModelSet fluids, ClientLevel biomeWorld) {
        long count = request.get(L, 8);
        int phase = request.get(I, 28);
        if ((phase != 0 && phase != 2 && phase != 3) || count < 0 ||
            request.byteSize() != Math.addExact(32, Math.multiplyExact(count, 20)) ||
            request.get(I, 24) != SectionSources.GAME_VERSION)
            throw new IllegalArgumentException("Invalid color request layout");
        response.header(SectionSources.GAME_VERSION, phase == 3 ? 4 : 3, request.get(L, 16),
                        request.get(L, 0))
                .l(count);
        if (phase == 3) {
            SectionBiomes.respond(request, response, biomeWorld);
            return;
        }
        response.i(Minecraft.getInstance().options.biomeBlendRadius().get());
        boolean biomes = false;
        var pos = new BlockPos.MutableBlockPos();
        for (long n = 0, at = 32; n < count; ++n, at += 20) {
            pos.set(request.get(I, at), request.get(I, at + 4), request.get(I, at + 8));
            var state = Block.stateById(request.get(I, at + 12));
            int slot = request.get(I, at + 16);
            var source = slot == -1 ? fluids.get(state.getFluidState()).tintSource()
                                    : blocks.getTintSource(state, slot);
            int kind = source == null ? 8 : KINDS.getOrDefault(source.getClass(), 0);
            int value = switch (kind) {
                case 0 -> source.colorInWorld(state, level, pos);
                case 5 ->
                    state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                            ? 1
                            : 0;
                case 6 -> state.getValue(BlockStateProperties.POWER);
                case 7 -> state.getValue(BlockStateProperties.AGE_7);
                case 8 -> {
                    var field = source == null ? null : CONSTANTS.get(source.getClass());
                    yield field == null ? -1 : (int)SectionBiomes.get(field, source);
                }
                default -> 0;
            };
            response.i(kind).i(value);
            biomes |= kind >= 1 && kind <= 5;
        }
        boolean definitions = phase == 2 && biomes;
        response.i(definitions ? 1 : 0);
        if (definitions)
            SectionBiomes.definitions(response, biomeWorld.getBiomeManager());
    }
}
