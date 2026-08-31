package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.terrain.LabPbrAtlasFrame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TexturePageLayoutTest {
    private static final LabPbrAtlasFrame.MaterialSource SOURCE =
            new LabPbrAtlasFrame.MaterialSource(new int[] {-1}, 1, 1, 1, 1, 1, 1);

    @Test
    void oversizedNonSquareTextureKeepsAnIndependentRectangularBacking() {
        LabPbrAtlasFrame.Sprite sprite = sprite(1, 3000, 100, 0);

        TexturePageLayout.Layout layout = TexturePageLayout.pack(
                List.of(sprite), LabPbrAtlasFrame.Sprite::normal, 6);

        assertEquals(List.of(new TexturePageLayout.Page(4096, 128)), layout.pages());
        TexturePageLayout.Placement placement = layout.placement(1);
        assertEquals(0, placement.page());
        assertEquals(0, placement.outerX());
        assertEquals(0, placement.outerY());
    }

    @Test
    void variedRectanglesAreDeterministicMipAlignedAndNonOverlapping() {
        ArrayList<LabPbrAtlasFrame.Sprite> sprites = new ArrayList<>();
        for (int id = 1; id <= 80; id++) {
            sprites.add(sprite(id, 7 + id % 29, 5 + id * 7 % 41, id % 3));
        }

        TexturePageLayout.Layout first = TexturePageLayout.pack(
                sprites, LabPbrAtlasFrame.Sprite::normal, 5);
        ArrayList<LabPbrAtlasFrame.Sprite> reversed = new ArrayList<>(sprites);
        java.util.Collections.reverse(reversed);
        TexturePageLayout.Layout second = TexturePageLayout.pack(
                reversed, LabPbrAtlasFrame.Sprite::normal, 5);

        assertEquals(first, second);
        assertTrue(first.pages().size() < sprites.size());
        for (LabPbrAtlasFrame.Sprite sprite : sprites) {
            TexturePageLayout.Placement placement = first.placement(sprite.textureId());
            int outerWidth = sprite.contentWidth() + 2 * sprite.padding();
            int outerHeight = sprite.contentHeight() + 2 * sprite.padding();
            int levels = Math.min(
                    5,
                    32 - Integer.numberOfLeadingZeros(Math.max(outerWidth, outerHeight)));
            int alignment = 1 << Math.max(0, levels - 1);
            assertEquals(0, placement.outerX() % alignment);
            assertEquals(0, placement.outerY() % alignment);
            TexturePageLayout.Page page = first.pages().get(placement.page());
            assertTrue(placement.outerX() + outerWidth <= page.width());
            assertTrue(placement.outerY() + outerHeight <= page.height());
        }
        for (int left = 0; left < sprites.size(); left++) {
            for (int right = left + 1; right < sprites.size(); right++) {
                LabPbrAtlasFrame.Sprite a = sprites.get(left);
                LabPbrAtlasFrame.Sprite b = sprites.get(right);
                TexturePageLayout.Placement pa = first.placement(a.textureId());
                TexturePageLayout.Placement pb = first.placement(b.textureId());
                if (pa.page() != pb.page()) {
                    continue;
                }
                assertFalse(overlaps(pa, a, pb, b));
                int sharedLevels = Math.min(textureLevels(a, 5), textureLevels(b, 5));
                for (int mip = 1; mip < sharedLevels; mip++) {
                    assertFalse(overlapsMip(pa, a, pb, b, mip));
                }
            }
        }
    }

    @Test
    void pageCountBeyondTheDescriptorAbiFailsExplicitly() {
        ArrayList<LabPbrAtlasFrame.Sprite> sprites = new ArrayList<>();
        for (int id = 1; id <= TexturePageLayout.MAX_PAGE_COUNT + 1; id++) {
            sprites.add(sprite(id, 2049, 1, 0));
        }

        assertThrows(
                IllegalStateException.class,
                () -> TexturePageLayout.pack(
                        sprites, LabPbrAtlasFrame.Sprite::normal, 1));
    }

    @Test
    void baseColorUsesItsIndependentSixtyFourPageAbi() {
        LabPbrAtlasFrame.ColorSource source = LabPbrAtlasFrame.ColorSource.copyOf(
                new int[4097], 4097, 1, 4097, 1);
        ArrayList<LabPbrAtlasFrame.Sprite> accepted = new ArrayList<>();
        for (int id = 1; id <= 17; id++) {
            accepted.add(new LabPbrAtlasFrame.Sprite(
                    id, 0, 0, 4097, 1, 0, source, null, null, -1));
        }

        TexturePageLayout.Layout layout = TexturePageLayout.packBaseColor(accepted, 1);

        assertEquals(17, layout.pages().size());
        ArrayList<LabPbrAtlasFrame.Sprite> rejected = new ArrayList<>();
        for (int id = 1; id <= 65; id++) {
            rejected.add(new LabPbrAtlasFrame.Sprite(
                    id, 0, 0, 4097, 1, 0, source, null, null, -1));
        }
        assertThrows(
                IllegalStateException.class,
                () -> TexturePageLayout.packBaseColor(rejected, 1));
    }

    @Test
    void missingChannelUsesOnlyTheDescriptorSafeDummyPage() {
        TexturePageLayout.Layout layout = TexturePageLayout.pack(
                List.of(sprite(1, 16, 16, 0)),
                LabPbrAtlasFrame.Sprite::specular,
                5);

        assertEquals(List.of(new TexturePageLayout.Page(1, 1)), layout.pages());
        assertTrue(layout.placements().isEmpty());
    }

    private static boolean overlaps(
            TexturePageLayout.Placement a,
            LabPbrAtlasFrame.Sprite aSprite,
            TexturePageLayout.Placement b,
            LabPbrAtlasFrame.Sprite bSprite) {
        int aWidth = aSprite.contentWidth() + 2 * aSprite.padding();
        int aHeight = aSprite.contentHeight() + 2 * aSprite.padding();
        int bWidth = bSprite.contentWidth() + 2 * bSprite.padding();
        int bHeight = bSprite.contentHeight() + 2 * bSprite.padding();
        return a.outerX() < b.outerX() + bWidth
                && b.outerX() < a.outerX() + aWidth
                && a.outerY() < b.outerY() + bHeight
                && b.outerY() < a.outerY() + aHeight;
    }

    private static boolean overlapsMip(
            TexturePageLayout.Placement a,
            LabPbrAtlasFrame.Sprite aSprite,
            TexturePageLayout.Placement b,
            LabPbrAtlasFrame.Sprite bSprite,
            int mip) {
        int aWidth = aSprite.mipWidth(mip);
        int aHeight = aSprite.mipHeight(mip);
        int bWidth = bSprite.mipWidth(mip);
        int bHeight = bSprite.mipHeight(mip);
        return a.mipX(mip) < b.mipX(mip) + bWidth
                && b.mipX(mip) < a.mipX(mip) + aWidth
                && a.mipY(mip) < b.mipY(mip) + bHeight
                && b.mipY(mip) < a.mipY(mip) + aHeight;
    }

    private static int textureLevels(LabPbrAtlasFrame.Sprite sprite, int requested) {
        int outerWidth = sprite.contentWidth() + 2 * sprite.padding();
        int outerHeight = sprite.contentHeight() + 2 * sprite.padding();
        return Math.min(
                requested,
                32 - Integer.numberOfLeadingZeros(Math.max(outerWidth, outerHeight)));
    }

    private static LabPbrAtlasFrame.Sprite sprite(
            int id, int width, int height, int padding) {
        return new LabPbrAtlasFrame.Sprite(
                id, 0, 0, width, height, padding, null, SOURCE, null, -1);
    }
}
