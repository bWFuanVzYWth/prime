package dev.primept.capture;
import java.lang.foreign.MemorySegment;
import dev.primept.abi.PrimeAbi.*;
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
    static MemorySegment respond(MemorySegment request, McSourceBatch response,
                                 BlockAndTintGetter level, BlockColors blocks,
                                 FluidStateModelSet fluids, ClientLevel biomeWorld) {
        int phase = PrimeMcRequests.phase(request);
        var identity = PrimeMcRequests.identity(request);
        long generation = PrimeMcIdentity.resource_generation(identity),
             epoch = PrimeMcIdentity.epoch(identity), batch = PrimeMcIdentity.batch(identity);
        if ((phase != 2 && phase != 3 && phase != 4) ||
            PrimeMcIdentity.game_version(identity) != SectionSources.GAME_VERSION)
            throw new IllegalArgumentException("Invalid color request layout");
        response.clear();
        if (phase == 4) {
            SectionBiomes.respond(request, response, biomeWorld);
            return response.biomes(SectionSources.GAME_VERSION, generation, epoch, batch);
        }
        long count = PrimeMcRequests.color_count(request);
        var requests = PrimeMcRequests.colors(request).reinterpret(
                Math.multiplyExact(count, PrimeMcColorRequest.SIZE));
        int radius = Minecraft.getInstance().options.biomeBlendRadius().get();
        boolean biomes = false;
        var pos = new BlockPos.MutableBlockPos();
        for (long n = 0; n < count; ++n) {
            var item = requests.asSlice(n * PrimeMcColorRequest.SIZE, PrimeMcColorRequest.SIZE);
            pos.set(PrimeMcColorRequest.x(item), PrimeMcColorRequest.y(item),
                    PrimeMcColorRequest.z(item));
            var state = Block.stateById(PrimeMcColorRequest.state(item));
            int slot = PrimeMcColorRequest.slot(item);
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
            var recipe = response.recipes.add();
            PrimeMcColorRecipe.kind(recipe, kind);
            PrimeMcColorRecipe.value(recipe, value);
            biomes |= kind >= 1 && kind <= 5;
        }
        boolean definitions = phase == 3 && biomes;
        if (definitions)
            SectionBiomes.definitions(response, biomeWorld.getBiomeManager());
        return response.colors(SectionSources.GAME_VERSION, generation, epoch, batch, radius);
    }
}
