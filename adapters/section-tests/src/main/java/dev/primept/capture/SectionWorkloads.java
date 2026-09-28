package dev.primept.capture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Deterministic closed inputs; every timed edit also has a full original-compiler oracle. */
final class SectionWorkloads {
    static void add(List<SectionCompilerOracle.Case> cases, BlockStateModel full, BlockState fence,
                    BlockStateModel post, BlockStateModel arm) throws Exception {
        var stone = Blocks.STONE.defaultBlockState();
        var water = Blocks.WATER.defaultBlockState();
        var fenceModel = SectionCompilerOracle.multipart(
                fence, List.of(new MultiPartModel.Selector<BlockStateModel>(s -> true, post),
                               new MultiPartModel.Selector<BlockStateModel>(s -> true, arm)));
        var models = Map.of(stone, full, fence, fenceModel);
        for (String kind : List.of("dense", "terraces", "multipart", "liquids", "checkerboard")) {
            var blocks = new HashMap<BlockPos, BlockState>();
            // 2x2x2 populated sections with an empty halo; all 27 requested sections are compiled.
            for (int y = 0; y < 32; ++y)
                for (int z = 0; z < 32; ++z)
                    for (int x = 0; x < 32; ++x) {
                        BlockState state = switch (kind) {
                            case "dense" -> stone;
                            case "terraces" -> y <= 5 + ((x * 13 + z * 7) % 19) ? stone : null;
                            case "multipart" ->
                                (x % 3 == 0 && y % 3 == 0 && z % 3 == 0) ? fence : null;
                            case "liquids" ->
                                y < 8    ? stone
                                : y == 8 ? water.setValue(BlockStateProperties.LEVEL, (x + z) % 8)
                                         : null;
                            default -> (x + y + z) % 2 == 0 ? stone : null;
                        };
                        if (state != null)
                            blocks.put(new BlockPos(x, y, z), state);
                    }
            cases.add(new SectionCompilerOracle.Case("bench_" + kind, Map.copyOf(blocks), models));
            // Interior edits touch no neighbor dependency; force exactly eight section rebuilds.
            for (int x = 0; x < 2; ++x)
                for (int z = 0; z < 2; ++z)
                    for (int y = 0; y < 2; ++y) {
                        var pos = new BlockPos(x * 16 + 8, y * 16 + 8, z * 16 + 8);
                        if (blocks.containsKey(pos))
                            blocks.remove(pos);
                        else
                            blocks.put(pos, stone);
                    }
            cases.add(new SectionCompilerOracle.Case("bench_" + kind + "_edited",
                                                     Map.copyOf(blocks), models));
        }
    }
}
