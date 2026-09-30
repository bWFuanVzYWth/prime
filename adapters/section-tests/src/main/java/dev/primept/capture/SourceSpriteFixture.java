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
    static byte[] atlas() {
        var pixels = new byte[16 * 16 * 4];
        java.util.Arrays.fill(pixels, (byte)-1);
        return pixels;
    }
}
