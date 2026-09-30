package dev.primept.capture;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Repeated dirty source batches. No window, Java compiler, FFM or GPU; includes actual visibility rules. */
final class TerrainRoutingCostCpuSmoke {
    static void run(String label) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.fabricmc.fabric.api.client.renderer.v1.Renderer.register(
                net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer.INSTANCE);
        var allocations = (ThreadMXBean)ManagementFactory.getThreadMXBean();
        allocations.setThreadAllocatedMemoryEnabled(true);
        var rows = new ArrayList<String>();
        rows.add(
                "case,sample,warmup,sections,route_ns,seal_ns,allocated_bytes,wire_bytes,placements");
        for (String kind : List.of("buried", "surface", "checker", "unculled")) {
            var region = FluidRouterCpuSmoke.blank(Region.class);
            region.kind = kind;
            var model = new Model(kind.equals("unculled"));
            var inbox = new LegacyTerrainInbox(true);
            var models =
                    new BlockStateModelSet(Map.of(Blocks.STONE.defaultBlockState(), model), model);
            try (var router = new TerrainRouter(inbox, models, null, new BlockColors())) {
                long warmupEnd = System.nanoTime() + 2_000_000_000L;
                int sample = -1;
                while (sample < 30) {
                    long allocated = allocations.getCurrentThreadAllocatedBytes(),
                         begin = System.nanoTime();
                    for (int section = 0; section < 64; ++section)
                        router.route(SectionPos.of(section - 32, 0, 0), region);
                    long routed = System.nanoTime();
                    var batch = inbox.seal();
                    long end = System.nanoTime(), bytes = 0, placements = 0;
                    allocated = allocations.getCurrentThreadAllocatedBytes() - allocated;
                    for (var item : batch.batches())
                        for (byte[] packet : item.packets()) {
                            bytes += packet.length;
                            var wire = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
                            if (wire.getInt(8) == 12)
                                placements += wire.getInt(64);
                        }
                    rows.add(kind + "," + sample + "," + (sample < 0) + ",64," + (routed - begin) +
                             "," + (end - routed) + "," + allocated + "," + bytes + "," +
                             placements);
                    if (inbox.failure() != null)
                        throw inbox.failure();
                    sample = sample >= 0                      ? sample + 1
                             : System.nanoTime() >= warmupEnd ? 0
                                                              : sample - 1;
                }
            }
        }
        Path path = Path.of(System.getProperty("primept.smoke.routingDirectory"))
                            .resolve("cost-" + label + ".csv");
        Files.createDirectories(path.getParent());
        Files.write(path, rows);
        System.out.println("PRIME_PT_ROUTING_COST_CPU_OK: " + path);
    }
    private static final class Region extends RenderSectionRegion {
        String kind;
        Region() {
            super(null, 0, 0, 0, null);
        }
        public BlockState getBlockState(BlockPos p) {
            boolean solid = switch (kind) {
                case "surface" -> p.getY() < 16;
                case "checker" -> ((p.getX() + p.getY() + p.getZ()) & 1) == 0;
                default -> true;
            };
            return (solid ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        }
    }
    private record Model(boolean unculled) implements BlockStateModel {
        public Object createGeometryKey(BlockAndTintGetter l, BlockPos p, BlockState s,
                                        RandomSource r) {
            return this;
        }
        public void emitQuads(QuadEmitter e, BlockAndTintGetter l, BlockPos p, BlockState s,
                              RandomSource r, Predicate<Direction> cull) {
            for (var face : Direction.values()) {
                e.pos(0, 0, 0, 0)
                        .pos(1, 1, 0, 0)
                        .pos(2, 1, 1, 0)
                        .pos(3, 0, 1, 0)
                        .color(-1, -1, -1, -1)
                        .tintIndex(-1)
                        .cullFace(unculled ? null : face)
                        .emit();
                if (unculled)
                    break;
            }
        }
        public void collectParts(RandomSource r, List<BlockStateModelPart> parts) {
            throw new AssertionError();
        }
        public Material.Baked particleMaterial() {
            return null;
        }
        public int materialFlags() {
            return 0;
        }
    }
}
