package dev.primept.capture;

import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Runs real Fabric source models; no Minecraft compiler or raster lighting is available. */
final class TerrainRouterCpuSmoke {
    static void run() throws Exception {
        var region = FluidRouterCpuSmoke.blank(Region.class);
        for (boolean keyed : new boolean[] {false, true}) {
            var inbox = new CaptureInbox(true);
            var model = new Model(keyed);
            var colors = new BlockColors();
            int[] tintCalls = {0};
            Thread owner = Thread.currentThread();
            colors.register(List.of(new BlockTintSource() {
                public int color(BlockState state) {
                    return 0xff70903f;
                }
                public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
                    check(Thread.currentThread() == owner,
                          "Source callbacks stay on their host owner");
                    ++tintCalls[0];
                    return 0xff70903f;
                }
            }),
                            Blocks.STONE);
            var models =
                    new BlockStateModelSet(Map.of(Blocks.STONE.defaultBlockState(), model), model);
            var expected = new SourceQuads();
            for (int x = 0; x < 2; ++x)
                for (int i = 0; i < 4; ++i)
                    expected.vertex(SourceQuads.CUTOUT, x + (i == 1 || i == 2 ? 1 : 0),
                                    i >= 2 ? 1 : 0, .25f, 0xff5b7e3b, i == 1 || i == 2 ? 1 : 0,
                                    i >= 2 ? 1 : 0);
            var reference = new CaptureInbox(true);
            reference.capture(reference.begin(SectionPos.of(0, 0, 0)), expected);
            try (var router = new TerrainRouter(inbox, models, null, colors)) {
                router.route(SectionPos.of(0, 0, 0), region);
                var batch = inbox.seal();
                check(inbox.failure() == null && model.calls == (keyed ? 1 : 2) &&
                              tintCalls[0] == 2,
                      "Exact model emission/key and actual tint callbacks");
                check(batch.batches().size() == (keyed ? 2 : 3),
                      "Definitions precede uses, retirements follow uses");
                var resource = wire(batch.batches().getFirst());
                check(resource.getInt(8) == 13 && resource.getInt(24) == (keyed ? 1 : 2),
                      "One batched resource publication, never one FFM call per model");
                var routed = wire(batch.batches().get(1));
                check(routed.getInt(8) == 12 && routed.getInt(64) == 2,
                      "Parametric section placements");
                write("terrain-" + keyed, batch, reference.seal());
                router.route(SectionPos.of(0, 0, 0), region);
                var second = inbox.seal();
                check(model.calls == (keyed ? 1 : 4) && tintCalls[0] == 4,
                      "Only formal immutable geometry keys suppress emission; tint remains source state");
                check(wire(second.batches().getFirst()).getInt(8) == (keyed ? 12 : 13),
                      "Retained native definitions are not retransmitted");
            }
            check(keyed == !inbox.seal().batches().isEmpty(),
                  "Source key ownership retires on close");
        }
        FluidRouterCpuSmoke.run();
        System.out.println(
                "PRIME_PT_TERRAIN_ROUTER_CPU_OK: source callbacks once; model assets batched; no SectionCompiler, AO, lighting, BufferBuilder; detached native fixtures written");
    }
    static void write(String name, CaptureInbox.Sealed actual, CaptureInbox.Sealed expected)
            throws Exception {
        Path root = Path.of(System.getProperty("primept.smoke.routingDirectory"));
        Files.createDirectories(root);
        try (var out = new DataOutputStream(Files.newOutputStream(root.resolve(name + ".bin")))) {
            for (var group : List.of(actual, expected)) {
                out.writeInt(group.batches().stream().mapToInt(b -> b.packets().size()).sum());
                for (var batch : group.batches())
                    for (byte[] packet : batch.packets()) {
                        out.writeInt(packet.length);
                        out.write(packet);
                    }
            }
        }
    }
    private static ByteBuffer wire(CaptureInbox.Batch batch) {
        return ByteBuffer.wrap(batch.packets().getFirst()).order(ByteOrder.LITTLE_ENDIAN);
    }
    private static final class Model implements BlockStateModel {
        final boolean keyed;
        int calls;
        Model(boolean keyed) {
            this.keyed = keyed;
        }
        public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state,
                                        RandomSource random) {
            return keyed ? this : null;
        }
        public void emitQuads(QuadEmitter e, BlockAndTintGetter level, BlockPos pos,
                              BlockState state, RandomSource random, Predicate<Direction> cull) {
            ++calls;
            e.pos(0, 0, 0, .25f)
                    .pos(1, 1, 0, .25f)
                    .pos(2, 1, 1, .25f)
                    .pos(3, 0, 1, .25f)
                    .uv(0, 0, 0)
                    .uv(1, 1, 0)
                    .uv(2, 1, 1)
                    .uv(3, 0, 1)
                    .color(0xffd0e0f0, 0xffd0e0f0, 0xffd0e0f0, 0xffd0e0f0)
                    .chunkLayer(ChunkSectionLayer.CUTOUT)
                    .cullFace(null)
                    .tintIndex(0)
                    .emit();
        }
        public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
            throw new AssertionError("Must respect custom emitQuads");
        }
        public Material.Baked particleMaterial() {
            return null;
        }
        public int materialFlags() {
            return 0;
        }
    }
    private static final class Region extends RenderSectionRegion {
        Region() {
            super(null, 0, 0, 0, null);
        }
        public BlockState getBlockState(BlockPos pos) {
            return (pos.getY() == 0 && pos.getZ() == 0 && pos.getX() >= 0 && pos.getX() < 2
                            ? Blocks.STONE
                            : Blocks.AIR)
                    .defaultBlockState();
        }
        public int getBrightness(net.minecraft.world.level.LightLayer layer, BlockPos pos) {
            throw new AssertionError("No raster light query");
        }
        public net.minecraft.world.level.CardinalLighting cardinalLighting() {
            throw new AssertionError("No raster shading");
        }
    }
    static void check(boolean ok, String message) {
        if (!ok)
            throw new AssertionError(message);
    }
}
