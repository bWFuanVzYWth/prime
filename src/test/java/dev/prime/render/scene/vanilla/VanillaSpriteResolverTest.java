// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.blaze3d.platform.NativeImage;
import dev.prime.mixin.accessor.SpriteContentsAccessor;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import dev.prime.render.terrain.LabPbrMaterialSet;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class VanillaSpriteResolverTest {
    @Test
    void cachesByRawIdentityAndCapturesOnlyPrimeOwnedFacts() {
        try (TestSprite first = new TestSprite(0xff12_3456);
                TestSprite second = new TestSprite(0xffab_cdef)) {
            SpriteId id = new SpriteId("example", "block/custom");
            VanillaSpriteResolver resolver = new VanillaSpriteResolver(
                    new LabPbrMaterialSet(
                            Map.of(id, 7),
                            java.util.Set.of(),
                            java.util.Set.of(),
                            Map.of(),
                            Map.of(),
                            Map.of()));

            CapturedSprite captured = resolver.resolve(first);

            assertSame(captured, resolver.resolve(first));
            CapturedSprite equivalent = resolver.resolve(second);
            assertNotSame(captured, equivalent);
            assertEquals(captured, equivalent);
            assertEquals(2, resolver.resolvedCount());
            assertEquals(id, captured.id());
            assertEquals(7, captured.textureId());
            assertEquals(16, captured.frameWidth());
            assertEquals(16, captured.frameHeight());
            assertEquals(0xff12_3456, captured.pixelView().argb(0, 0));
        }
    }

    private static final class TestSprite extends TextureAtlasSprite {
        private TestSprite(int argb) {
            this(new TestContents(argb));
        }

        private TestSprite(TestContents contents) {
            super(
                    Identifier.fromNamespaceAndPath("example", "atlas"),
                    contents,
                    32,
                    16,
                    16,
                    0,
                    0);
        }
    }

    private static final class TestContents
            extends SpriteContents
            implements SpriteContentsAccessor {
        private final NativeImage image;

        private TestContents(int argb) {
            this(new NativeImage(16, 16, true), argb);
        }

        private TestContents(NativeImage image, int argb) {
            super(
                    Identifier.fromNamespaceAndPath("example", "block/custom"),
                    new FrameSize(16, 16),
                    image);
            this.image = image;
            this.image.setPixel(0, 0, argb);
        }

        @Override
        public NativeImage prime$originalImage() {
            return this.image;
        }

        @Override
        public NativeImage[] prime$byMipLevel() {
            return new NativeImage[] {this.image};
        }
    }
}
