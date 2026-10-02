package dev.primept.capture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;

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
        try (var out = new SourcePages()) {
            var resources = new SourceSprites();
            var sprite = new SourceSpriteFixture(contents, 0, 0);
            resources.prepare(out, sprite);
            long bytes = out.bytes();
            resources.prepare(out, sprite);
            if (out.bytes() != bytes)
                throw new AssertionError("sprite definition was retransmitted");
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
        try (var out = new SourcePages()) {
            field.set(capture, java.util.List.of(used, unused));
            var sources = new SourceSprites();
            sources.prepareAtlas(out);
            var data = read(out);
            staticDefinition(data, 1, used);
            staticDefinition(data, 2, unused);
            if (data.hasRemaining())
                throw new AssertionError("complete atlas dictionary has unexpected records");
            long preparedBytes = out.bytes();
            if (preparedBytes == 0 || sources.prepare(out, used) != 1 ||
                sources.prepare(out, unused) != 2 || out.bytes() != preparedBytes)
                throw new AssertionError("section sprite use must reuse resource dictionary IDs");
            out.clear();
            for (int section = 0; section < 1000; ++section)
                sources.prepareAtlas(out);
            if (out.bytes() != 0)
                throw new AssertionError("atlas dictionary was retransmitted per section");
            // A fresh source owner is created for the new capture/catalog epoch.
            field.set(capture, java.util.List.of(replacement, unused));
            new SourceSprites().prepareAtlas(out);
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
