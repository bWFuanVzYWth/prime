package dev.primept.capture;

import dev.primept.NativeBridge;
import dev.primept.abi.PrimeAbi.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.joml.Vector3f;

/** Real MC palette/resource fields -> one FFM response -> native scene; no device/window. */
final class SectionSourcesCpuSmoke {
    private static final ValueLayout.OfInt I =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong L =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static void run() throws Exception {
        SourceSpriteFixture.verifyCompleteAtlasDictionary();
        SectionPlacementOracle.run();
        var material = new BakedQuad.MaterialInfo(SourceSpriteFixture.create(0, 0, 16),
                                                  ChunkSectionLayer.CUTOUT, null, -1, true, 0);
        var quad = new BakedQuad(
                new Vector3f(0, 0, 0), new Vector3f(1, 0, 0), new Vector3f(1, 1, 0),
                new Vector3f(0, 1, 0), UVPair.pack(.1f, .2f), UVPair.pack(.3f, .4f),
                UVPair.pack(.5f, .6f), UVPair.pack(.7f, .8f), Direction.SOUTH, material);
        var collection = new QuadCollection.Builder().addUnculledFace(quad).build();
        var model = new SingleVariant(new SimpleModelWrapper(collection, false, null));
        var opaque = new Opaque();
        var sources = new SectionSources(
                new BlockStateModelSet(Map.of(Blocks.STONE.defaultBlockState(), model,
                                              Blocks.DIRT.defaultBlockState(), opaque),
                                       model),
                new net.minecraft.client.renderer.block.FluidStateModelSet(Map.of(), null));
        var air = section(Blocks.AIR.defaultBlockState());
        var solid = section(Blocks.STONE.defaultBlockState());
        var unknown = section(Blocks.DIRT.defaultBlockState());
        Path fixture = Path.of(System.getProperty("primept.smoke.routingDirectory"),
                               "mc-section-source.bin");
        Files.createDirectories(fixture.getParent());
        SourceSpriteFixture.verifyAnimation(fixture.getParent());
        try (var events = new McSourceBatch(); var output = new McSourceBatch();
             var resources = new McSourceBatch(); var wire = new SourcePages();
             var bridge =
                     new NativeBridge(Path.of(System.getProperty("primept.smoke.nativeLibrary")))) {
            bridge.reset(1);
            sources.section(resources, output, 0, 0, 0, solid);
            sources.section(resources, output, 0, 0, 0, unknown);
            sources.section(resources, output, 0, 0, 0, air);
            output.clear();
            bridge.resources(resources.resources(SectionSources.GAME_VERSION, 1, 1, 1, 0, true, 16,
                                                 16, SourceSpriteFixture.atlas()));
            for (long batch = 1; batch <= 5; ++batch) {
                events.clear();
                output.clear();
                // Retain first resource definitions for the independent on-disk legacy oracle.
                if (batch != 1)
                    resources.clear();
                if (batch == 1)
                    for (int x = 0; x < 4; ++x)
                        for (int z = 0; z < 4; ++z)
                            event(events, 1, x, 0, z);
                if (batch >= 3)
                    for (int n = 0; n < 1000; ++n)
                        event(events, 3, 0, 0, 0);
                MemorySegment request = bridge.requestSections(
                        events.plan(SectionSources.GAME_VERSION, 1, 1, batch, 16, 16, 3, 0, 3,
                                    new int[] {0, 3, 0, 3}, batch));
                long count = PrimeMcRequests.section_count(request);
                check(count == (batch == 1   ? 64
                                : batch == 2 ? 0
                                             : 1),
                      "Rust request count after actual duplicate events: " + count);
                var requests = PrimeMcRequests.sections(request).reinterpret(
                        count * PrimeMcSectionRequest.SIZE);
                // First preparation may leave only world-dependent model selections missing.
                long preparedStates = resources.states.count();
                for (long n = 0; n < count; ++n) {
                    var item = requests.asSlice(n * PrimeMcSectionRequest.SIZE,
                                                PrimeMcSectionRequest.SIZE);
                    int x = PrimeMcSectionRequest.x(item), y = PrimeMcSectionRequest.y(item),
                        z = PrimeMcSectionRequest.z(item);
                    sources.section(resources, output, x, y, z,
                                    batch == 5                                 ? unknown
                                    : batch != 4 && x == 0 && y == 0 && z == 0 ? solid
                                                                               : air);
                }
                if (batch == 1) {
                    SourceFixtureWire.source(resources, output, wire, batch);
                    write(wire, fixture);
                    // This fixture's models are immutable and were all prepared before planning.
                    check(resources.states.count() == preparedStates,
                          "initial immutable resource preparation missed a state");
                } else if (resources.hasResources()) {
                    bridge.resources(resources.resources(SectionSources.GAME_VERSION, 1, 1, batch,
                                                         batch, false, 0, 0, new byte[0]));
                }
                bridge.sections(output.sections(SectionSources.GAME_VERSION, 1, 1, batch));
                String diagnostics = bridge.cpuDiagnostics();
                check(diagnostics.contains("active=64"), diagnostics);
                check(diagnostics.contains("triangles=" +
                                           (batch == 1   ? 2
                                            : batch == 5 ? 12
                                                         : 0) +
                                           " "),
                      diagnostics);
                if (batch == 2 || batch == 3)
                    check(diagnostics.contains("compiled=0"), diagnostics);
            }
            check(opaque.calls == 0,
                  "Opaque model callbacks must not run or be probed by the field router");
        }
        System.out.println(
                "PRIME_MC_SECTION_SOURCE_FFM_OK: MC " + SectionSources.GAME_VERSION +
                ", real palette/quad fields, 64 complete sections, idle=0 requested, 1000 dirty=1 request, unchanged=0 compile, solid-to-air; no GPU/window");
    }
    private static final class Opaque
            implements net.minecraft.client.renderer.block.dispatch.BlockStateModel {
        int calls;
        public void collectParts(
                net.minecraft.util.RandomSource random,
                java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>
                        output) {
            ++calls;
            throw new AssertionError("Opaque model callback");
        }
        public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() {
            ++calls;
            throw new AssertionError("Opaque particle callback");
        }
        public int materialFlags() {
            ++calls;
            throw new AssertionError("Opaque material callback");
        }
    }
    private static void event(McSourceBatch b, int kind, int x, int y, int z) {
        var e = b.events.add();
        PrimeMcEvent.kind(e, kind);
        PrimeMcEvent.x(e, x);
        PrimeMcEvent.y(e, y);
        PrimeMcEvent.z(e, z);
    }
    static LevelChunkSection section(net.minecraft.world.level.block.state.BlockState state)
            throws Exception {
        var section = FluidRouterCpuSmoke.blank(LevelChunkSection.class);
        var states =
                new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                                        Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        states.set(0, 0, 0, state);
        var field = LevelChunkSection.class.getDeclaredField("states");
        field.setAccessible(true);
        field.set(section, states);
        return section;
    }
    static void write(SourcePages pages, Path file) throws Exception {
        var table = pages.table();
        try (var out = Files.newOutputStream(file)) {
            for (long i = 0; i < pages.pageCount(); ++i) {
                var page = table.get(ValueLayout.ADDRESS, i * 16)
                                   .reinterpret(table.get(L, i * 16 + 8));
                out.write(page.toArray(ValueLayout.JAVA_BYTE));
            }
        }
    }
    private static void check(boolean pass, String message) {
        if (!pass)
            throw new AssertionError(message);
    }
}
