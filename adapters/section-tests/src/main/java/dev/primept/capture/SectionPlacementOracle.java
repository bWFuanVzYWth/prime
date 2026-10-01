package dev.primept.capture;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Actual getOffset/getSeed oracle; expected values are not a copied placement formula. */
final class SectionPlacementOracle {
    static void run() throws Exception {
        var sources = new SectionPlacementSources(SectionSources.BED_SEED_DECLARATION);
        var representatives = new LinkedHashMap<SectionPlacementSources.Placement, BlockState>();
        for (var state : Block.BLOCK_STATE_REGISTRY) {
            var placement = sources.prepare(state);
            if (placement.offset() == 3 || placement.seed() == 6)
                throw new AssertionError("Unknown vanilla placement declaration: " + state);
            representatives.putIfAbsent(placement, state);
        }
        var positions = new ArrayList<BlockPos>();
        for (var point : new int[][] {{0, 0, 0},
                                      {1, 2, 3},
                                      {-1, -1, -1},
                                      {-30000000, -64, 30000000},
                                      {30000000, 319, -30000000},
                                      {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE},
                                      {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE}})
            positions.add(new BlockPos(point[0], point[1], point[2]));
        var random = new Random(0x706c6163656d656eL);
        for (int i = 0; i < 512; ++i)
            positions.add(new BlockPos(random.nextInt(), random.nextInt(), random.nextInt()));
        int count = representatives.size() * positions.size();
        var bytes = ByteBuffer.allocate(16 + count * 60).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x504c4d43).putInt(1).putInt(SectionSources.GAME_VERSION).putInt(count);
        for (var entry : representatives.entrySet()) {
            var declaration = entry.getKey();
            for (var position : positions) {
                var state = entry.getValue();
                Vec3 offset = state.getOffset(position);
                bytes.putInt(declaration.offset())
                        .putFloat(declaration.horizontal())
                        .putFloat(declaration.vertical())
                        .putInt(declaration.seed())
                        .putInt(position.getX())
                        .putInt(position.getY())
                        .putInt(position.getZ())
                        .putLong(state.getSeed(position))
                        .putDouble(offset.x)
                        .putDouble(offset.y)
                        .putDouble(offset.z);
            }
        }
        var directory = Path.of(System.getProperty("primept.smoke.routingDirectory"));
        Files.createDirectories(directory);
        Files.write(directory.resolve("placement-oracle.bin"), bytes.array());
        unknownSources(sources);
        System.out.println("PRIME_PLACEMENT_HOST_OK: MC " + SectionSources.GAME_VERSION +
                           ", rules=" + representatives.size() + ", cases=" + count +
                           ", actual getOffset/getSeed; unknown callbacks=0; no GPU/window");
    }
    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.of().setId(
                BuiltInRegistries.BLOCK.getResourceKey(Blocks.STONE).orElseThrow());
    }
    private static void unknownSources(SectionPlacementSources sources) throws Exception {
        var custom = new Unknown(properties().offsetType(BlockBehaviour.OffsetType.XZ));
        var declaration = sources.prepare(custom.defaultBlockState());
        if (declaration.offset() != 3 || declaration.seed() != 6 || custom.calls != 0)
            throw new AssertionError("Unknown overrides must be described without invoking them");
        var properties = properties();
        var field = BlockBehaviour.Properties.class.getDeclaredField("offsetFunction");
        field.setAccessible(true);
        int[] calls = {0};
        Object function = Proxy.newProxyInstance(field.getType().getClassLoader(),
                                                 new Class<?>[] {field.getType()},
                                                 (proxy, method, arguments) -> {
                                                     ++calls[0];
                                                     return new Vec3(.1, .2, .3);
                                                 });
        field.set(properties, function);
        declaration = sources.prepare(new Block(properties).defaultBlockState());
        if (declaration.offset() != 3 || declaration.seed() != 0 || calls[0] != 0)
            throw new AssertionError("Unknown offset lambda must not be sampled or inferred");
    }
    private static final class Unknown extends Block {
        int calls;
        Unknown(BlockBehaviour.Properties properties) {
            super(properties);
        }
        @Override
        protected float getMaxHorizontalOffset() {
            ++calls;
            return .125f;
        }
        @Override
        protected long getSeed(BlockState state, BlockPos position) {
            ++calls;
            return 7;
        }
    }
}
