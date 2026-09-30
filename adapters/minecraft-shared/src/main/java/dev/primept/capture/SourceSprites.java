package dev.primept.capture;

import dev.primept.mixin.SpriteContentsAccessor;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Actual immutable sprite fields, sent once per resource epoch. Rust owns sampling and animation. */
final class SourceSprites {
    private static final Field ANIMATION = field(SpriteContents.class, "animatedTexture"),
                               FRAMES = field(ANIMATION.getType(), "frames"),
                               INTERPOLATE = field(ANIMATION.getType(), "interpolateFrames");
    private final IdentityHashMap<TextureAtlasSprite, Integer> ids = new IdentityHashMap<>();

    int prepare(SourcePages out, TextureAtlasSprite sprite) {
        Integer known = ids.get(sprite);
        if (known != null)
            return known;
        int id = ids.size() + 1;
        ids.put(sprite, id);
        var contents = sprite.contents();
        Object animation = get(ANIMATION, contents);
        var images = ((SpriteContentsAccessor)contents).primept$mipImages();
        out.i(6).i(id)
                .string(contents.name().toString())
                .f(sprite.getU0())
                .f(sprite.getV0())
                .f(sprite.getU1())
                .f(sprite.getV1())
                .i(contents.width())
                .i(contents.height())
                .i(images.length);
        for (int mip = 0; mip < images.length; ++mip) {
            var image = images[mip];
            out.i(image.getWidth()).i(image.getHeight());
            // The static base image already belongs to the shared atlas. No duplicate pixels.
            if (mip == 0 && animation == null)
                out.i(0);
            else {
                var pixels = image.getPixelBytes();
                out.i(pixels.remaining() / 4).pixels(pixels);
            }
        }
        if (animation == null)
            out.i(0).i(0);
        else {
            var frames = (List<?>)get(FRAMES, animation);
            out.i((boolean)get(INTERPOLATE, animation) ? 1 : 0).i(frames.size());
            for (Object frame : frames)
                out.i((int)get(field(frame.getClass(), "index"), frame))
                        .i((int)get(field(frame.getClass(), "time"), frame));
        }
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
