package dev.primept.capture;

import com.mojang.blaze3d.platform.NativeImage;
import dev.primept.NativeBridge;
import dev.primept.abi.PrimeAbi.*;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Real host resource values, without a GPU texture or a client window. */
final class SourceSpriteFixture extends TextureAtlasSprite {
    private SourceSpriteFixture(SpriteContents contents, int x, int y) {
        super(TextureAtlas.LOCATION_BLOCKS, contents, 16, 16, x, y, 0);
    }
    static TextureAtlasSprite create(int x, int y, int size) {
        var pixels = new NativeImage(size, size, true);
        for (int a = 0; a < size; ++a)
            for (int b = 0; b < size; ++b)
                pixels.setPixel(a, b, -1);
        return new SourceSpriteFixture(
                new SpriteContents(Identifier.withDefaultNamespace("prime_test"),
                                   new FrameSize(size, size), pixels),
                x, y);
    }
    static TextureAtlasSprite load(net.minecraft.resources.Identifier texture) throws Exception {
        try (var input = SourceSpriteFixture.class.getResourceAsStream(
                     "/assets/" + texture.getNamespace() + "/textures/" + texture.getPath() +
                     ".png")) {
            if (input == null)
                throw new AssertionError("Missing vanilla texture: " + texture);
            var image = NativeImage.read(input);
            if (image.getWidth() != 16 || image.getHeight() != 16) {
                image.close();
                throw new AssertionError("Fixture requires a 16x16 vanilla texture: " + texture);
            }
            return new SourceSpriteFixture(
                    new SpriteContents(texture, new FrameSize(16, 16), image), 0, 0);
        }
    }
    static void verifyAnimation(java.nio.file.Path directory) throws Exception {
        var image = new NativeImage(8, 4, true);
        for (int y = 0; y < 4; ++y)
            for (int x = 0; x < 8; ++x)
                image.setPixel(x, y, x < 4 ? 0x40203040 : 0xe08090a0);
        var contents = new SpriteContents(Identifier.withDefaultNamespace("prime_animated"),
                                          new FrameSize(4, 4), image);
        var animationField = SpriteContents.class.getDeclaredField("animatedTexture");
        animationField.setAccessible(true);
        var infoType = Class.forName(SpriteContents.class.getName() + "$FrameInfo");
        var infoConstructor = infoType.getDeclaredConstructor(int.class, int.class);
        infoConstructor.setAccessible(true);
        var frames = java.util.List.of(infoConstructor.newInstance(1, 3),
                                       infoConstructor.newInstance(0, 7));
        var constructor = animationField.getType().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object animation = constructor.newInstance(contents, frames, 2, true);
        animationField.set(contents, animation);
        var stateType = Class.forName(SpriteContents.class.getName() + "$AnimationState");
        var stateConstructor = stateType.getDeclaredConstructors()[0];
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance(contents, animation, null, null);
        var frame = stateType.getDeclaredField("frame");
        var subFrame = stateType.getDeclaredField("subFrame");
        frame.setAccessible(true);
        subFrame.setAccessible(true);
        var tick = stateType.getMethod("tick");
        try (var out = new SourcePages(); var batch = new McSourceBatch()) {
            var resources = new SourceSprites();
            var sprite = new SourceSpriteFixture(contents, 0, 0);
            resources.prepare(batch, sprite);
            long bytes = batch.bytes();
            resources.prepare(batch, sprite);
            if (batch.bytes() != bytes)
                throw new AssertionError("sprite definition was retransmitted");
            SourceFixtureWire.resources(batch, out);
            out.i(31);
            for (int i = 0; i < 31; ++i) {
                int selected = frame.getInt(state), sub = subFrame.getInt(state);
                out.i(i).i(selected == 0 ? 1 : 0).i(sub).i(selected == 0 ? 3 : 7);
                tick.invoke(state);
            }
            SectionSourcesCpuSmoke.write(out, directory.resolve("sprite-animation.bin"));
        }
        contents.close();
    }
    static void verifyCompleteAtlasDictionary() throws Exception {
        var capture = dev.primept.PrimeClient.CAPTURE;
        var field = CaptureInbox.class.getDeclaredField("sprites");
        field.setAccessible(true);
        Object previous = field.get(capture);
        var used = create(0, 0, 8);
        var unused = create(8, 0, 8);
        var replacement = create(0, 8, 8);
        try (var out = new SourcePages(); var batch = new McSourceBatch()) {
            field.set(capture, java.util.List.of(used, unused));
            var sources = new SourceSprites();
            sources.prepareAtlas(batch);
            SourceFixtureWire.resources(batch, out);
            var data = read(out);
            staticDefinition(data, 1, used);
            staticDefinition(data, 2, unused);
            if (data.hasRemaining())
                throw new AssertionError("complete atlas dictionary has unexpected records");
            long preparedBytes = batch.bytes();
            if (preparedBytes == 0 || sources.prepare(batch, used) != 1 ||
                sources.prepare(batch, unused) != 2 || batch.bytes() != preparedBytes)
                throw new AssertionError("section sprite use must reuse resource dictionary IDs");
            out.clear();
            batch.clear();
            for (int section = 0; section < 1000; ++section)
                sources.prepareAtlas(batch);
            if (batch.bytes() != 0)
                throw new AssertionError("atlas dictionary was retransmitted per section");
            // A fresh source owner is created for the new capture/catalog epoch.
            field.set(capture, java.util.List.of(replacement, unused));
            new SourceSprites().prepareAtlas(batch);
            SourceFixtureWire.resources(batch, out);
            data = read(out);
            staticDefinition(data, 1, replacement);
            staticDefinition(data, 2, unused);
            if (data.hasRemaining())
                throw new AssertionError("new source owner must publish only its atlas dictionary");
        } finally {
            field.set(capture, previous);
            used.contents().close();
            unused.contents().close();
            replacement.contents().close();
        }
        System.out.println(
                "PRIME_PT_COMPLETE_ATLAS_SOURCE_OK: no LabPBR, used and unused actual sprites, stable IDs, 1000 sections=0 retransmission, new owner complete replacement; no GPU/window");
    }
    static void verifyAtlasReload(BlockStateModelSet models, FluidStateModelSet fluids,
                                  LevelChunkSection solid) throws Exception {
        var capture = new CaptureInbox();
        capture.enable();
        long epoch = capture.epoch();
        var first = create(0, 0, 16);
        var replacement = create(0, 0, 16);
        var air = SectionSourcesCpuSmoke.section(Blocks.AIR.defaultBlockState());
        long previousGeneration = 0, previousAtlas = 0;
        try (var events = new McSourceBatch(); var output = new McSourceBatch();
             var resources = new McSourceBatch();
             var bridge = new NativeBridge(
                     java.nio.file.Path.of(System.getProperty("primept.smoke.nativeLibrary")))) {
            bridge.reset(epoch);
            for (long batch = 1; batch <= 2; ++batch) {
                var sprite = batch == 1 ? first : replacement;
                capture.captureAtlas(new SpriteLoader.Preparations(
                        16, 16, 0, sprite, java.util.Map.of(sprite.contents().name(), sprite),
                        java.util.concurrent.CompletableFuture.completedFuture(null)));
                if (capture.failure() != null)
                    throw new AssertionError("Actual atlas capture failed", capture.failure());
                if (capture.epoch() != epoch)
                    throw new AssertionError("Atlas reload replaced the world epoch");
                var atlas = capture.atlas();
                long generation = capture.resourceGeneration(models, fluids);
                if (atlas.version() <= previousAtlas || generation <= previousGeneration ||
                    capture.resourceGeneration(models, fluids) != generation)
                    throw new AssertionError(
                            "Atlas reload must advance only its resource generation");
                previousAtlas = atlas.version();
                previousGeneration = generation;

                // The replacement dictionary and old terrain withdrawal are a resource transaction
                // in the same world. Repeated identical atlas pixels still require fresh definitions.
                var sources = new SectionSources(models, fluids);
                events.clear();
                output.clear();
                resources.clear();
                sources.section(resources, output, 0, 0, 0, solid);
                sources.section(resources, output, 0, 0, 0, air);
                if (resources.states.count() == 0 || resources.models.count() == 0 ||
                    resources.sprites.count() == 0)
                    throw new AssertionError(
                            "New resource generation did not republish source definitions");
                bridge.resources(resources.resources(SectionSources.GAME_VERSION, generation, epoch,
                                                     0, batch, true, atlas.width(), atlas.height(),
                                                     atlas.rgba()));
                if (batch == 1) {
                    for (int x = 0; x < 4; ++x) {
                        for (int z = 0; z < 4; ++z) {
                            var event = events.events.add();
                            PrimeMcEvent.kind(event, 1);
                            PrimeMcEvent.x(event, x);
                            PrimeMcEvent.y(event, 0);
                            PrimeMcEvent.z(event, z);
                        }
                    }
                }
                output.clear();
                var request = bridge.requestSections(
                        events.plan(SectionSources.GAME_VERSION, generation, epoch, batch, 16, 16,
                                    3, 0, 3, new int[] {0, 3, 0, 3}, batch));
                long count = PrimeMcRequests.section_count(request);
                if (count == 0)
                    throw new AssertionError(
                            "Resource replacement did not request terrain recompilation");
                var sections = PrimeMcRequests.sections(request).reinterpret(
                        Math.multiplyExact(count, PrimeMcSectionRequest.SIZE));
                for (long i = 0; i < count; ++i) {
                    var item = sections.asSlice(i * PrimeMcSectionRequest.SIZE,
                                                PrimeMcSectionRequest.SIZE);
                    int x = PrimeMcSectionRequest.x(item), y = PrimeMcSectionRequest.y(item),
                        z = PrimeMcSectionRequest.z(item);
                    sources.section(resources, output, x, y, z,
                                    x == 0 && y == 0 && z == 0 ? solid : air);
                }
                bridge.sections(
                        output.sections(SectionSources.GAME_VERSION, generation, epoch, batch));
                var diagnostics = bridge.cpuDiagnostics();
                if (!diagnostics.contains("active=64") || !diagnostics.contains("triangles=2 ") ||
                    diagnostics.contains("compiled=0"))
                    throw new AssertionError("Atlas reload lost actual geometry recompilation: " +
                                             diagnostics);
            }
        } finally {
            first.contents().close();
            replacement.contents().close();
        }
        System.out.println(
                "PRIME_PT_ATLAS_RELOAD_HISTORY_DOMAIN_OK: actual atlas capture keeps world epoch, resource generation advances and republishes dictionary, native recompiles geometry in the same epoch; no GPU/window");
    }
    private static java.nio.ByteBuffer read(SourcePages pages) {
        var bytes = new java.io.ByteArrayOutputStream();
        var table = pages.table();
        var length = java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED.withOrder(
                java.nio.ByteOrder.LITTLE_ENDIAN);
        for (long i = 0; i < pages.pageCount(); ++i) {
            var page = table.get(java.lang.foreign.ValueLayout.ADDRESS, i * 16)
                               .reinterpret(table.get(length, i * 16 + 8));
            bytes.writeBytes(page.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        }
        return java.nio.ByteBuffer.wrap(bytes.toByteArray())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
    }
    private static void staticDefinition(java.nio.ByteBuffer data, int id,
                                         TextureAtlasSprite sprite) {
        if (data.getInt() != 6 || data.getInt() != id)
            throw new AssertionError("complete atlas sprite identity");
        int nameBytes = data.getInt();
        byte[] name = new byte[nameBytes];
        data.get(name);
        data.position(data.position() + (4 - nameBytes % 4) % 4);
        if (!new String(name, java.nio.charset.StandardCharsets.UTF_8)
                     .equals(sprite.contents().name().toString()))
            throw new AssertionError("complete atlas sprite source name");
        for (float bound :
             new float[] {sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()})
            if (data.getFloat() != bound)
                throw new AssertionError("complete atlas sprite source window");
        int size = sprite.contents().width();
        if (data.getInt() != size || data.getInt() != sprite.contents().height() ||
            data.getInt() != 1 || data.getInt() != size || data.getInt() != size ||
            data.getInt() != 0 || data.getInt() != 0 || data.getInt() != 0)
            throw new AssertionError("static base must borrow atlas pixels without animation");
    }
    static byte[] atlas() {
        var pixels = new byte[16 * 16 * 4];
        java.util.Arrays.fill(pixels, (byte)-1);
        return pixels;
    }
}
