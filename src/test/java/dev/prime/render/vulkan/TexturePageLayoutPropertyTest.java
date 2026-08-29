package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.terrain.LabPbrAtlasFrame;
import dev.prime.test.PrimeProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.junit.jupiter.api.Test;

final class TexturePageLayoutPropertyTest {
    private static final LabPbrAtlasFrame.MaterialSource SOURCE =
            new LabPbrAtlasFrame.MaterialSource(new int[] {-1}, 1, 1, 1, 1, 1, 1);

    @Test
    void randomCatalogsAreOrderIndependentMipSafeAndDescriptorBounded() {
        Generator<List<Integer>> catalogs = Generator.listsOf(
                IntDistribution.uniform(0, 48), Generator.integers());

        PrimeProperties.check(
                "texture-page-layout",
                256,
                catalogs,
                TexturePageLayoutPropertyTest::assertCatalog);
    }

    private static void assertCatalog(List<Integer> encoded) {
        ArrayList<LabPbrAtlasFrame.Sprite> sprites = new ArrayList<>(encoded.size());
        for (int index = 0; index < encoded.size(); index++) {
            int value = encoded.get(index);
            int width = 1 + Math.floorMod(value, 384);
            int height = 1 + Math.floorMod(Integer.rotateRight(value, 9), 384);
            int padding = Math.floorMod(Integer.rotateRight(value, 18), 8);
            boolean present = (value & 0x4000_0000) == 0;
            sprites.add(new LabPbrAtlasFrame.Sprite(
                    index + 1,
                    0,
                    0,
                    width,
                    height,
                    padding,
                    present ? SOURCE : null,
                    null,
                    -1));
        }
        int mipLevels = 1 + Math.floorMod(encoded.hashCode(), 6);

        TexturePageLayout.Layout first = TexturePageLayout.pack(
                sprites, LabPbrAtlasFrame.Sprite::normal, mipLevels);
        ArrayList<LabPbrAtlasFrame.Sprite> reversed = new ArrayList<>(sprites);
        Collections.reverse(reversed);
        TexturePageLayout.Layout second = TexturePageLayout.pack(
                reversed, LabPbrAtlasFrame.Sprite::normal, mipLevels);

        assertEquals(first, second);
        assertTrue(first.pages().size() >= 1);
        assertTrue(first.pages().size() <= TexturePageLayout.MAX_PAGE_COUNT);
        for (TexturePageLayout.Page page : first.pages()) {
            assertTrue(page.width() > 0 && page.height() > 0);
        }
        for (LabPbrAtlasFrame.Sprite sprite : sprites) {
            TexturePageLayout.Placement placement = first.placement(sprite.textureId());
            if (sprite.normal() == null) {
                assertNull(placement);
                continue;
            }
            int outerWidth = sprite.contentWidth() + 2 * sprite.padding();
            int outerHeight = sprite.contentHeight() + 2 * sprite.padding();
            int levels = Math.min(
                    mipLevels,
                    32 - Integer.numberOfLeadingZeros(Math.max(outerWidth, outerHeight)));
            int alignment = 1 << Math.max(0, levels - 1);
            assertEquals(0, placement.outerX() % alignment);
            assertEquals(0, placement.outerY() % alignment);
            TexturePageLayout.Page page = first.pages().get(placement.page());
            assertTrue(placement.outerX() + outerWidth <= page.width());
            assertTrue(placement.outerY() + outerHeight <= page.height());
        }
        assertNoOverlap(sprites, first, mipLevels);
    }

    private static void assertNoOverlap(
            List<LabPbrAtlasFrame.Sprite> sprites,
            TexturePageLayout.Layout layout,
            int requestedMipLevels) {
        for (int left = 0; left < sprites.size(); left++) {
            LabPbrAtlasFrame.Sprite a = sprites.get(left);
            TexturePageLayout.Placement pa = layout.placement(a.textureId());
            if (pa == null) {
                continue;
            }
            for (int right = left + 1; right < sprites.size(); right++) {
                LabPbrAtlasFrame.Sprite b = sprites.get(right);
                TexturePageLayout.Placement pb = layout.placement(b.textureId());
                if (pb == null || pa.page() != pb.page()) {
                    continue;
                }
                int sharedLevels = Math.min(
                        textureLevels(a, requestedMipLevels),
                        textureLevels(b, requestedMipLevels));
                for (int mip = 0; mip < sharedLevels; mip++) {
                    assertFalse(overlaps(pa, a, pb, b, mip),
                            "textureIds=" + a.textureId() + "," + b.textureId()
                                    + " mip=" + mip);
                }
            }
        }
    }

    private static boolean overlaps(
            TexturePageLayout.Placement a,
            LabPbrAtlasFrame.Sprite aSprite,
            TexturePageLayout.Placement b,
            LabPbrAtlasFrame.Sprite bSprite,
            int mip) {
        return a.mipX(mip) < b.mipX(mip) + bSprite.mipWidth(mip)
                && b.mipX(mip) < a.mipX(mip) + aSprite.mipWidth(mip)
                && a.mipY(mip) < b.mipY(mip) + bSprite.mipHeight(mip)
                && b.mipY(mip) < a.mipY(mip) + aSprite.mipHeight(mip);
    }

    private static int textureLevels(LabPbrAtlasFrame.Sprite sprite, int requested) {
        int outerWidth = sprite.contentWidth() + 2 * sprite.padding();
        int outerHeight = sprite.contentHeight() + 2 * sprite.padding();
        return Math.min(
                requested,
                32 - Integer.numberOfLeadingZeros(Math.max(outerWidth, outerHeight)));
    }
}
