package dev.primept.capture;

import dev.primept.mixin.SpriteContentsAccessor;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import dev.primept.abi.PrimeAbi.*;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Actual immutable sprite fields, sent once per resource epoch. Rust owns sampling and animation. */
final class SourceSprites {
    private static final Field ANIMATION = field(SpriteContents.class, "animatedTexture"),
                               FRAMES = field(ANIMATION.getType(), "frames"),
                               INTERPOLATE = field(ANIMATION.getType(), "interpolateFrames");
    private final IdentityHashMap<TextureAtlasSprite, Integer> ids = new IdentityHashMap<>();
    private final LabPbrSources materials = new LabPbrSources();
    private boolean atlasPrepared;

    void prepareAtlas(McSourceBatch out) {
        if (atlasPrepared)
            return;
        atlasPrepared = true;
        var sprites = dev.primept.PrimeClient.CAPTURE.sprites();
        // Publish the complete resource dictionary before section-local use. Rust can prepare
        // resource-owned coverage once, including sprites first encountered by later terrain.
        for (var sprite : sprites)
            prepare(out, sprite);
    }

    int prepare(McSourceBatch out, TextureAtlasSprite sprite) {
        Integer known = ids.get(sprite);
        if (known != null)
            return known;
        int id = ids.size() + 1;
        ids.put(sprite, id);
        var contents = sprite.contents();
        Object animation = get(ANIMATION, contents);
        var images = ((SpriteContentsAccessor)contents).primept$mipImages();
        long firstImage = out.images.count();
        for (int mip = 0; mip < images.length; ++mip) {
            var image = images[mip];
            // The static base image already belongs to the shared atlas. No duplicate pixels.
            if (mip == 0 && animation == null)
                out.emptyImage(image.getWidth(), image.getHeight());
            else {
                var pixels = image.getPixelBytes();
                out.image(image.getWidth(), image.getHeight(), pixels);
            }
        }
        long firstFrame = out.frames.count();
        if (animation != null) {
            var frames = (List<?>)get(FRAMES, animation);
            for (Object frame : frames) {
                var value = out.frames.add();
                PrimeMcAnimationFrame.frame(value,
                                            (int)get(field(frame.getClass(), "index"), frame));
                PrimeMcAnimationFrame.duration(value,
                                               (int)get(field(frame.getClass(), "time"), frame));
            }
        }
        var material = materials.prepare(out, contents.name());
        var name = out.text(contents.name().toString());
        var value = out.sprites.add();
        PrimeMcSprite.id(value, id);
        PrimeMcSprite.interpolate(
                value, animation != null && (boolean)get(INTERPOLATE, animation) ? 1 : 0);
        float[] bounds = {sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()};
        for (int i = 0; i < 4; i++)
            PrimeMcSprite.bounds(value, i, bounds[i]);
        PrimeMcSprite.extent(value, 0, contents.width());
        PrimeMcSprite.extent(value, 1, contents.height());
        name.write(PrimeMcSprite.name(value));
        new McSourceBatch.Range(firstImage, images.length).write(PrimeMcSprite.images(value));
        new McSourceBatch.Range(firstFrame, out.frames.count() - firstFrame)
                .write(PrimeMcSprite.frames(value));
        PrimeMcSprite.normal_image(value, material[0]);
        PrimeMcSprite.specular_image(value, material[1]);
        return id;
    }
    private static Field field(Class<?> type, String name) {
        try {
            var f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    private static Object get(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
