package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.terrain.CanonicalColorEncoding;
import dev.prime.render.terrain.LabPbrAtlasFrame;
import dev.prime.render.terrain.LabPbrMaterialSet;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

final class MaterialTexturePagesTest {
    @Test
    void capturedBaseColorPixelsAreImmutableAndExplicitlyRetirable() {
        int[] pixels = {0xff102030};
        LabPbrAtlasFrame.ColorSource source =
                LabPbrAtlasFrame.ColorSource.copyOf(pixels, 1, 1, 1, 1);
        LabPbrAtlasFrame.Sprite sprite = new LabPbrAtlasFrame.Sprite(
                1, 0, 0, 1, 1, 0, source, null, null, -1);
        LabPbrAtlasFrame.Snapshot snapshot = new LabPbrAtlasFrame.Snapshot(
                1, 1, 1, LabPbrMaterialSet.EMPTY, List.of(sprite));

        pixels[0] = 0;
        int[] exposed = source.pixels();
        exposed[0] = 0;
        LabPbrAtlasFrame.Snapshot retired = snapshot.withoutBaseColorSources();

        assertArrayEquals(new int[] {0xff102030}, source.pixels());
        assertEquals(source, snapshot.sprites().getFirst().baseColor());
        assertNull(retired.sprites().getFirst().baseColor());
        assertEquals(snapshot.materials(), retired.materials());
    }

    @Test
    void baseColorMipFilteringOccursInLinearRec2020WithLinearCoverage() {
        LabPbrAtlasFrame.ColorSource source = LabPbrAtlasFrame.ColorSource.copyOf(
                new int[] {0x00000000, 0xffffffff}, 2, 1, 2, 1);
        float[] filtered = new float[4];

        source.filtered(
                LabPbrAtlasFrame.AnimationSample.ZERO,
                0.0,
                0.0,
                2.0,
                1.0,
                2,
                1,
                filtered);

        assertEquals(0.5F, filtered[0], 1.0E-6F);
        assertEquals(0.5F, filtered[1], 1.0E-6F);
        assertEquals(0.5F, filtered[2], 1.0E-6F);
        assertEquals(127.5F, filtered[3], 0.0F);
    }

    @Test
    void canonicalAnimationCacheInterpolatesBeforeItsOnlyHalfQuantization() {
        LabPbrAtlasFrame.ColorSource source = LabPbrAtlasFrame.ColorSource.copyOf(
                new int[] {0xff000000, 0xffffffff}, 1, 2, 1, 1);
        LabPbrAtlasFrame.Sprite sprite = new LabPbrAtlasFrame.Sprite(
                1, 0, 0, 1, 1, 0, source, null, null, 0);
        TexturePageLayout.Placement placement =
                new TexturePageLayout.Placement(0, 0, 0, sprite);
        ColorAnimationFrames frames = ColorAnimationFrames.create(placement, source, 1);
        ByteBuffer target = MemoryUtil.memAlloc(8);
        try {
            long address = MemoryUtil.memAddress(target);
            frames.write(
                    address,
                    new LabPbrAtlasFrame.AnimationSample(0, 1, 500),
                    0);
            long encoded = Short.toUnsignedLong(MemoryUtil.memGetShort(address))
                    | (long) Short.toUnsignedInt(MemoryUtil.memGetShort(address + 2L)) << 16
                    | (long) Short.toUnsignedInt(MemoryUtil.memGetShort(address + 4L)) << 32
                    | (long) Short.toUnsignedInt(MemoryUtil.memGetShort(address + 6L)) << 48;
            CanonicalColorEncoding.Color decoded =
                    CanonicalColorEncoding.decodeRgba16f(encoded);

            assertEquals(0.5F, decoded.red(), 0.0F);
            assertEquals(0.5F, decoded.green(), 0.0F);
            assertEquals(0.5F, decoded.blue(), 0.0F);
            assertEquals(1.0F, decoded.alpha(), 0.0F);
        } finally {
            MemoryUtil.memFree(target);
            frames.destroy();
        }
    }

    @Test
    void baseColorFrameExtentMustMatchTheSprite() {
        LabPbrAtlasFrame.ColorSource source = LabPbrAtlasFrame.ColorSource.copyOf(
                new int[] {-1, -1}, 2, 1, 2, 1);

        assertThrows(
                IllegalArgumentException.class,
                () -> new LabPbrAtlasFrame.Sprite(
                        1, 0, 0, 1, 1, 0, source, null, null, -1));
    }

    @Test
    void capturedMaterialPixelsAreImmutable() {
        int[] pixels = {0xff102030};
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                pixels, 1, 1, 1, 1, 1, 1);

        pixels[0] = 0;
        int[] exposed = source.pixels();
        exposed[0] = 0;

        assertArrayEquals(new int[] {0xff102030}, source.pixels());
    }

    @Test
    void frameRejectsMissingAnimationSamples() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff102030}, 1, 1, 1, 1, 1, 1);
        LabPbrAtlasFrame.Sprite sprite = new LabPbrAtlasFrame.Sprite(
                1, 0, 0, 1, 1, 0, null, source, null, 0);
        LabPbrAtlasFrame.Snapshot snapshot = new LabPbrAtlasFrame.Snapshot(
                1, 1, 1, LabPbrMaterialSet.EMPTY, List.of(sprite));

        assertThrows(
                IllegalArgumentException.class,
                () -> new LabPbrAtlasFrame(0, snapshot, List.of()));
    }

    @Test
    void atlasAndAnimationBudgetsKeepOffsetsAboveTwoGibibytes() {
        long pageBytes = MaterialTexturePages.totalMipBytes(32_768, 32_768, 16);
        long animationBytes = MaterialTexturePages.animationEndOffset(
                0L, 32_768, 32_768, true, true);

        assertTrue(pageBytes > Integer.MAX_VALUE);
        assertEquals(8L * 32_768L * 32_768L, animationBytes);
    }

    @Test
    void argbSourcesAreWrittenAsVulkanRgbaBytes() {
        ByteBuffer bytes = ByteBuffer.allocate(4);

        MaterialTexturePages.writeArgb(bytes, 0, 0xff123456);

        assertArrayEquals(
                new byte[] {0x12, 0x34, 0x56, (byte) 0xff},
                bytes.array());
    }

    @Test
    void textureRecordGroupsEachChannelHotFieldsWithoutChangingItsExactCodes() {
        LabPbrAtlasFrame.Sprite sprite = new LabPbrAtlasFrame.Sprite(
                7, 0, 0, 31, 17, 2, null, null, null, -1);
        TexturePageLayout.Placement base =
                new TexturePageLayout.Placement(5, 64, 128, sprite);
        TexturePageLayout.Placement normal =
                new TexturePageLayout.Placement(6, 256, 512, sprite);
        TexturePageLayout.Placement optical =
                new TexturePageLayout.Placement(7, 1024, 2048, sprite);
        ByteBuffer bytes = MemoryUtil.memAlloc(32);
        try {
            MaterialTexturePages.writeTextureRecord(
                    MemoryUtil.memAddress(bytes), sprite, base, normal, optical, 4, 3, 2);

            assertArrayEquals(
                    new int[] {
                        66 | 130 << 16,
                        31 | 17 << 16,
                        4 | 5 << 8,
                        258 | 514 << 16,
                        6 | 7 << 8 | 3 << 16 | 2 << 24,
                        1026 | 2050 << 16,
                        0,
                        0
                    },
                    new int[] {
                        bytes.getInt(0),
                        bytes.getInt(4),
                        bytes.getInt(8),
                        bytes.getInt(12),
                        bytes.getInt(16),
                        bytes.getInt(20),
                        bytes.getInt(24),
                        bytes.getInt(28)
                    });
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    @Test
    void textureRecordUsesExactMissingAuxiliaryPageCodes() {
        LabPbrAtlasFrame.Sprite sprite = new LabPbrAtlasFrame.Sprite(
                1, 0, 0, 1, 1, 0, null, null, null, -1);
        TexturePageLayout.Placement base =
                new TexturePageLayout.Placement(0, 0, 0, sprite);
        ByteBuffer bytes = MemoryUtil.memAlloc(32);
        try {
            MaterialTexturePages.writeTextureRecord(
                    MemoryUtil.memAddress(bytes), sprite, base, null, null, 0, 0, 0);

            assertEquals(0x0000_ffff, bytes.getInt(16));
            assertEquals(0, bytes.getInt(12));
            assertEquals(0, bytes.getInt(20));
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    @Test
    void animatedAuxiliaryMapsUseBaseFrameIndicesAndInterpolationProgress() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff000000, 0xffff0000},
                1,
                2,
                1,
                1,
                1,
                2);

        int blended = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(0, 1, 500),
                0.0,
                0.0,
                1.0,
                1.0,
                1,
                1,
                true);

        assertEquals(0xff800000, blended);
    }

    @Test
    void cachedAnimationFramesPreserveFilteredInterpolation() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff000000, 0xffff0000},
                1,
                2,
                1,
                1,
                1,
                2);
        LabPbrAtlasFrame.Sprite sprite = new LabPbrAtlasFrame.Sprite(
                1, 0, 0, 1, 1, 0, null, null, source, 0);
        TexturePageLayout.Placement placement =
                new TexturePageLayout.Placement(0, 0, 0, sprite);
        MaterialAnimationFrames frames =
                MaterialAnimationFrames.create(placement, source, 1, true);
        ByteBuffer target = MemoryUtil.memAlloc(4);
        try {
            frames.write(
                    MemoryUtil.memAddress(target),
                    new LabPbrAtlasFrame.AnimationSample(0, 1, 500),
                    0);

            assertArrayEquals(
                    new byte[] {(byte) 0x80, 0, 0, (byte) 0xff},
                    new byte[] {target.get(0), target.get(1), target.get(2), target.get(3)});
        } finally {
            MemoryUtil.memFree(target);
            frames.destroy();
        }
    }

    @Test
    void singleFrameAuxiliaryMapsRemainStaticForAnimatedBaseSprites() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff204060},
                1,
                1,
                1,
                1,
                1,
                4);

        int sampled = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(3, 0, 750),
                0.0,
                0.0,
                1.0,
                1.0,
                1,
                1,
                true);

        assertEquals(0xff204060, sampled);
    }

    @Test
    void normalMipFilteringPreservesDirectionAndEncodesDistributionRoughness() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xffcc8080, 0xff3380c0},
                2,
                1,
                2,
                1,
                2,
                1);

        int sampled = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(0, 0, 0),
                0.0,
                0.0,
                2.0,
                1.0,
                2,
                1);

        int roughness = sampled >>> 24;
        assertTrue(roughness > 0 && roughness < 255);
        assertTrue(Math.abs((sampled >>> 16 & 0xff) - 128) <= 1);
        assertTrue(Math.abs((sampled >>> 8 & 0xff) - 128) <= 1);
        assertEquals(160, sampled & 0xff);
    }

    @Test
    void flatNormalMipAddsNoDistributionRoughness() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff8080ff}, 1, 1, 1, 1, 1, 1);

        int sampled = source.filtered(
                LabPbrAtlasFrame.AnimationSample.ZERO,
                0.0,
                0.0,
                1.0,
                1.0,
                1,
                1);

        assertEquals(0, sampled >>> 24);
        assertEquals(128, sampled >>> 16 & 0xff);
        assertEquals(128, sampled >>> 8 & 0xff);
        assertEquals(255, sampled & 0xff);
    }

    @Test
    void specularFilteringTreatsThe255AlphaSentinelAsZeroEmission() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff000000, 0xfe000000},
                2,
                1,
                2,
                1,
                2,
                1);

        int sampled = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(0, 0, 0),
                0.0,
                0.0,
                2.0,
                1.0,
                2,
                1,
                true);

        assertEquals(0x7f000000, sampled);
    }

    @Test
    void specularFilteringPreservesAnAllSentinelRegion() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff000000, 0xff000000},
                2,
                1,
                2,
                1,
                2,
                1);

        int sampled = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(0, 0, 0),
                0.0,
                0.0,
                2.0,
                1.0,
                2,
                1,
                true);

        assertEquals(0xff000000, sampled);
    }

    @Test
    void specularAnimationInterpolatesDecodedEmissionRatherThanTheSentinelByte() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff000000, 0xfe000000},
                1,
                2,
                1,
                1,
                1,
                2);

        int sampled = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(0, 1, 500),
                0.0,
                0.0,
                1.0,
                1.0,
                1,
                1,
                true);

        assertEquals(0x7f000000, sampled);
    }

    @Test
    void specularFilteringUsesTheTargetCenterForCategoricalCodes() {
        int[] pixels = {
            0x0a20_e540, 0x1e40_e641,
            0x3260_ed41, 0x4680_ee42,
            0x5aa0_fe42, 0x6ec0_ff40
        };
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                pixels, 6, 1, 6, 1, 6, 1);
        int[] expectedCodes = {0xe641, 0xee42, 0xff40};

        for (int targetX = 0; targetX < 3; targetX++) {
            int sampled = source.filtered(
                    LabPbrAtlasFrame.AnimationSample.ZERO,
                    targetX,
                    0.0,
                    targetX + 1.0,
                    1.0,
                    3,
                    1,
                    true);

            assertEquals(expectedCodes[targetX], sampled & 0x0000_ffff);
            int first = pixels[targetX * 2];
            int second = pixels[targetX * 2 + 1];
            assertEquals(
                    ((first >>> 24) + (second >>> 24) + 1) / 2,
                    sampled >>> 24);
            assertEquals(
                    ((first >>> 16 & 0xff) + (second >>> 16 & 0xff) + 1) / 2,
                    sampled >>> 16 & 0xff);
        }
    }

    @Test
    void specularAnimationKeepsCurrentFrameCategoricalCodes() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0x1020_e540, 0x3040_e641},
                1,
                2,
                1,
                1,
                1,
                2);

        int sampled = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(0, 1, 500),
                0.0,
                0.0,
                1.0,
                1.0,
                1,
                1,
                true);

        assertEquals(0x2030_e540, sampled);
    }
}
