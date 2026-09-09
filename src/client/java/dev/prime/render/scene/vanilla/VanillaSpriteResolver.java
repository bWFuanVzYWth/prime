// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.platform.NativeImage;
import dev.prime.mixin.accessor.SpriteContentsAccessor;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import dev.prime.render.scene.SpritePixelView;
import dev.prime.render.terrain.LabPbrMaterialSet;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.IdentityHashMap;
import java.util.Objects;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/** Worker-confined adapter that resolves each raw vanilla sprite once per captured cluster. */
public final class VanillaSpriteResolver {
    private final LabPbrMaterialSet materials;
    private final IdentityHashMap<TextureAtlasSprite, CapturedSprite> resolved =
            new IdentityHashMap<>();

    public VanillaSpriteResolver(LabPbrMaterialSet materials) {
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    public CapturedSprite resolve(TextureAtlasSprite sprite) {
        Objects.requireNonNull(sprite, "sprite");
        return this.resolved.computeIfAbsent(sprite, this::capture);
    }

    int resolvedCount() {
        return this.resolved.size();
    }

    private CapturedSprite capture(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        Identifier name = contents.name();
        boolean animated = contents.isAnimated();
        IntList sourceFrames = animated ? contents.getUniqueFrames() : null;
        int[] frames;
        if (sourceFrames == null || sourceFrames.isEmpty()) {
            frames = new int[] {0};
        } else {
            frames = sourceFrames.toIntArray();
        }
        NativeImage image = ((SpriteContentsAccessor) (Object) contents).prime$originalImage();
        SpritePixelView pixels = image == null || image.isClosed()
                ? null
                : new NativeImageView(image);
        SpriteId id = new SpriteId(name.getNamespace(), name.getPath());
        return new CapturedSprite(
                id,
                this.materials.textureId(id),
                contents.width(),
                contents.height(),
                animated,
                frames,
                pixels);
    }

    private record NativeImageView(NativeImage image) implements SpritePixelView {
        private NativeImageView {
            Objects.requireNonNull(image, "image");
        }

        @Override
        public int imageWidth() {
            return this.image.getWidth();
        }

        @Override
        public int imageHeight() {
            return this.image.getHeight();
        }

        @Override
        public int argb(int x, int y) {
            return this.image.getPixel(x, y);
        }
    }
}
