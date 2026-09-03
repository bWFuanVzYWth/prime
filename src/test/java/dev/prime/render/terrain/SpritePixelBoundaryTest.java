package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import dev.prime.render.scene.SpritePixelView;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.EXTOpacityMicromap;

final class SpritePixelBoundaryTest {
    @Test
    void missingPixelsUseUnknownOpacityAndUniformEmission() {
        CapturedSprite sprite = sprite(
                "missing", 0.0F, 0.0F, 1.0F, 1.0F, false, new int[] {0}, null);
        OpacityMicromapData.Builder opacity = new OpacityMicromapData.Builder();
        opacity.addTriangle(sprite, uv(0.0F, 0.0F), uv(1.0F, 0.0F), uv(0.0F, 1.0F));

        OpacityMicromapData data = opacity.build();
        EmissionDistribution emission = EmissionDistribution.build(new EmissionDistribution.Key(
                sprite,
                uv(0.0F, 0.0F),
                uv(1.0F, 0.0F),
                uv(0.0F, 1.0F),
                -1,
                true,
                1.0F,
                null));

        assertEquals(0, data.blockCount());
        assertEquals(
                EXTOpacityMicromap.VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_UNKNOWN_OPAQUE_EXT,
                data.triangleIndices()[0]);
        assertSame(EmissionDistribution.uniform(), emission);
    }

    @Test
    void animatedFrameFactsDriveOpacityWithoutMinecraftObjects() {
        int[] pixels = new int[32 * 16];
        for (int y = 0; y < 16; y++) {
            Arrays.fill(pixels, y * 32, y * 32 + 16, 0xffff_ffff);
        }
        ArrayPixels view = new ArrayPixels(pixels, 32, 16);
        CapturedSprite animated = sprite(
                "animated", 0.0F, 0.0F, 1.0F, 1.0F, true, new int[] {0, 1}, view);
        CapturedSprite singleUniqueFrame = sprite(
                "single_unique", 0.0F, 0.0F, 1.0F, 1.0F, true, new int[] {0}, view);

        assertEquals(
                EXTOpacityMicromap.VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_UNKNOWN_OPAQUE_EXT,
                bakeTriangle(animated));
        assertEquals(
                EXTOpacityMicromap.VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_OPAQUE_EXT,
                bakeTriangle(singleUniqueFrame));
    }

    @Test
    void staticPowerOfTwoSpritesUseAdaptiveTwoStateMicromaps() {
        assertAdaptiveTwoState(8, 8, 3);
        assertAdaptiveTwoState(16, 16, 4);
        assertAdaptiveTwoState(32, 32, 5);
        assertAdaptiveTwoState(16, 32, 5);
        assertAdaptiveTwoState(64, 64, 6);
        assertAdaptiveTwoState(128, 128, 7);
        assertAdaptiveTwoState(256, 256, 8);
    }

    @Test
    void ironChainLocalUvSliceUsesUnknownAtNonDyadicBoundaries() {
        CapturedSprite chain = patternedSprite("iron_chain", 16, 16);
        OpacityMicromapData.Builder builder = new OpacityMicromapData.Builder();
        int first = uv(0.0F, 0.0F);
        int second = uv(3.0F / 16.0F, 0.0F);
        int third = uv(0.0F, 1.0F);
        builder.addTriangle(chain, first, second, third);

        OpacityMicromapData data = builder.build();

        assertEquals(1, data.blockCount());
        assertEquals(OpacityMicromapData.FOUR_STATE_FORMAT, data.blockFormats()[0]);
        assertEquals(6, data.blockSubdivisionLevels()[0]);
        OpacityMicromapData.BakedBlock block = new OpacityMicromapData.BakedBlock(
                data.blockFormats()[0], data.blockSubdivisionLevels()[0], data.blocks());
        int unknown = 0;
        for (int index = 0;
                index < OpacityMicromapData.microTriangleCount(block.subdivisionLevel());
                index++) {
            unknown += block.state(index) == 3 ? 1 : 0;
        }
        assertTrue(unknown > 0);
        assertEquals(4, OpacityMicromapData.maximumRepeatedSize(
                chain,
                first,
                second,
                third,
                OpacityMicromapData.MAX_SUBDIVISION_LEVEL + 2));
    }

    @Test
    void nonDyadicMicrotriangleAccountsForInteriorTexels() {
        CapturedSprite sprite = patternedSprite("interior_overlap", 16, 16);
        OpacityMicromapData.Builder builder = new OpacityMicromapData.Builder();
        builder.addTriangle(
                sprite,
                uv(4.0F / 16.0F, 15.0F / 16.0F),
                uv(8.0F / 16.0F, 12.0F / 16.0F),
                uv(1.0F / 16.0F, 1.0F));

        OpacityMicromapData data = builder.build();
        OpacityMicromapData.BakedBlock block = new OpacityMicromapData.BakedBlock(
                data.blockFormats()[0], data.blockSubdivisionLevels()[0], data.blocks());
        int level = block.subdivisionLevel();
        int birdIndex = OpacityMicromapData.barycentricsToSpaceFillingCurveIndex(
                1.0F / 96.0F, 1.0F / 96.0F, level);

        assertEquals(6, level);
        assertEquals(OpacityMicromapData.FOUR_STATE_FORMAT, block.format());
        assertEquals(3, block.state(birdIndex));
    }

    @Test
    void skewedDyadicCoordinatesUseConservativeFourStateCoverage() {
        CapturedSprite sprite = patternedSprite("skewed_dyadic", 16, 16);
        OpacityMicromapData.Builder builder = new OpacityMicromapData.Builder();
        builder.addTriangle(
                sprite,
                uv(0.0F, 0.0F),
                uv(1.0F, 0.5F),
                uv(0.0F, 1.0F));

        OpacityMicromapData data = builder.build();

        assertEquals(1, data.blockCount());
        assertEquals(OpacityMicromapData.FOUR_STATE_FORMAT, data.blockFormats()[0]);
        assertEquals(6, data.blockSubdivisionLevels()[0]);
    }

    @Test
    void repeatedUvUsesTwoStateOnlyForExactlyAlignedMappings() {
        CapturedSprite sprite = patternedSprite("repeated", 16, 16);
        OpacityMicromapData.Builder alignedBuilder = new OpacityMicromapData.Builder();
        alignedBuilder.addRepeatedTriangle(
                sprite,
                uv(0.0F, 0.0F),
                uv(1.0F, 0.0F),
                uv(0.0F, 1.0F),
                2,
                0.0F,
                0.0F,
                2.0F,
                0.0F,
                0.0F,
                2.0F);
        OpacityMicromapData aligned = alignedBuilder.build();

        OpacityMicromapData.Builder misalignedBuilder = new OpacityMicromapData.Builder();
        misalignedBuilder.addRepeatedTriangle(
                sprite,
                uv(0.0F, 0.0F),
                uv(3.0F / 16.0F, 0.0F),
                uv(0.0F, 1.0F),
                2,
                0.0F,
                0.0F,
                2.0F,
                0.0F,
                0.0F,
                2.0F);
        OpacityMicromapData misaligned = misalignedBuilder.build();

        assertEquals(OpacityMicromapData.TWO_STATE_FORMAT, aligned.blockFormats()[0]);
        assertEquals(5, aligned.blockSubdivisionLevels()[0]);
        assertEquals(OpacityMicromapData.FOUR_STATE_FORMAT, misaligned.blockFormats()[0]);
        assertEquals(7, misaligned.blockSubdivisionLevels()[0]);
    }

    @Test
    void nonPowerOfTwoCoverageUsesUnknownAtUnrepresentableBoundaries() {
        CapturedSprite sprite = patternedSprite("non_power_of_two", 24, 24);
        OpacityMicromapData.Builder builder = new OpacityMicromapData.Builder();
        builder.addTriangle(sprite, uv(0.0F, 0.0F), uv(1.0F, 0.0F), uv(0.0F, 1.0F));

        OpacityMicromapData data = builder.build();

        assertEquals(1, data.blockCount());
        assertEquals(OpacityMicromapData.FOUR_STATE_FORMAT, data.blockFormats()[0]);
        assertEquals(5, data.blockSubdivisionLevels()[0]);
    }

    @Test
    void deviceLimitUsesUnknownWhereTwoStateCannotResolveEveryTexel() {
        int size = 512;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            Arrays.fill(pixels, y * size, y * size + size / 2, 0xffff_ffff);
        }
        CapturedSprite sprite = sprite(
                "extreme",
                0.0F,
                0.0F,
                1.0F,
                1.0F,
                size,
                size,
                false,
                new int[] {0},
                new ArrayPixels(pixels, size, size));
        OpacityMicromapData.Builder builder = new OpacityMicromapData.Builder(8);
        builder.addTriangle(sprite, uv(0.0F, 0.0F), uv(1.0F, 0.0F), uv(0.0F, 1.0F));

        OpacityMicromapData data = builder.build();

        assertEquals(1, data.blockCount());
        assertEquals(OpacityMicromapData.FOUR_STATE_FORMAT, data.blockFormats()[0]);
        assertEquals(8, data.blockSubdivisionLevels()[0]);
    }

    @Test
    void formatSpecificDeviceLimitsDoNotCapStaticTwoStateTextures() {
        OpacityMicromapData.Builder staticBuilder = new OpacityMicromapData.Builder(8, 4);
        staticBuilder.addTriangle(
                patternedSprite("static_128", 128, 128),
                uv(0.0F, 0.0F),
                uv(1.0F, 0.0F),
                uv(0.0F, 1.0F));
        OpacityMicromapData staticData = staticBuilder.build();

        OpacityMicromapData.Builder irregularBuilder = new OpacityMicromapData.Builder(8, 4);
        irregularBuilder.addTriangle(
                patternedSprite("irregular_24", 24, 24),
                uv(0.0F, 0.0F),
                uv(1.0F, 0.0F),
                uv(0.0F, 1.0F));
        OpacityMicromapData irregularData = irregularBuilder.build();

        assertEquals(OpacityMicromapData.TWO_STATE_FORMAT, staticData.blockFormats()[0]);
        assertEquals(7, staticData.blockSubdivisionLevels()[0]);
        assertEquals(0, irregularData.blockCount());
        assertEquals(
                EXTOpacityMicromap.VK_OPACITY_MICROMAP_SPECIAL_INDEX_FULLY_UNKNOWN_OPAQUE_EXT,
                irregularData.triangleIndices()[0]);

        int size = 128;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            Arrays.fill(pixels, y * size, y * size + 11, 0xffff_ffff);
        }
        CapturedSprite slice = sprite(
                "misaligned_static_128",
                0.0F,
                0.0F,
                1.0F,
                1.0F,
                size,
                size,
                false,
                new int[] {0},
                new ArrayPixels(pixels, size, size));
        OpacityMicromapData.Builder sliceBuilder = new OpacityMicromapData.Builder(8, 4);
        sliceBuilder.addTriangle(
                slice,
                uv(0.0F, 0.0F),
                uv(3.0F / 16.0F, 0.0F),
                uv(0.0F, 1.0F));
        OpacityMicromapData sliceData = sliceBuilder.build();

        assertEquals(OpacityMicromapData.FOUR_STATE_FORMAT, sliceData.blockFormats()[0]);
        assertEquals(4, sliceData.blockSubdivisionLevels()[0]);
    }

    @Test
    void absentVoxelPixelsPreserveTheStandardQuad() {
        CapturedSprite sprite = sprite(
                "voxel_missing", 0.0F, 0.0F, 1.0F, 1.0F, false, new int[] {0}, null);

        CpuClusterMesh cluster = translate(sprite, CapturedSectionGeometry.Layer.OPAQUE);

        assertTrue(cluster.voxelMeshes().isEmpty());
        assertEquals(0, cluster.voxelInstances().count());
        assertEquals(2L, cluster.triangleLayout().opaqueTriangleCount());
    }

    @Test
    void invalidLayoutsAndPixelFailuresRemainFailFast() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CapturedSprite(
                        new SpriteId("fixture", "zero_texture_id"),
                        0,
                        16,
                        16,
                        false,
                        new int[] {0},
                        null));

        CapturedSprite failing = sprite(
                "failing",
                0.0F,
                0.0F,
                1.0F,
                1.0F,
                false,
                new int[] {0},
                new FailingPixels());
        OpacityMicromapData.Builder failingOpacity = new OpacityMicromapData.Builder();
        assertThrows(
                PixelFailure.class,
                () -> failingOpacity.addTriangle(
                        failing,
                        uv(0.0F, 0.0F),
                        uv(1.0F, 0.0F),
                        uv(0.0F, 1.0F)));
        assertThrows(
                PixelFailure.class,
                () -> EmissionDistribution.build(new EmissionDistribution.Key(
                        failing,
                        uv(0.0F, 0.0F),
                        uv(1.0F, 0.0F),
                        uv(0.0F, 1.0F),
                        -1,
                        true,
                        1.0F,
                        null)));
        assertThrows(
                PixelFailure.class,
                () -> translate(failing, CapturedSectionGeometry.Layer.OPAQUE));
    }

    @Test
    void compiledOutputNeverReadsBorrowedPixelsAfterTranslation() {
        GuardedPixels pixels = new GuardedPixels();
        CapturedSprite sprite = sprite(
                "lease_bound", 0.0F, 0.0F, 1.0F, 1.0F, false, new int[] {0}, pixels);
        CpuClusterMesh mesh = translate(sprite, CapturedSectionGeometry.Layer.OPAQUE);
        assertTrue(pixels.readCount > 0);
        int readsAtLeaseClose = pixels.readCount;
        pixels.active = false;
        mesh.segments().forEach(segment -> segment.primitiveRecords());
        mesh.voxelMeshes().forEach(voxel -> voxel.primitiveRecords());
        assertEquals(readsAtLeaseClose, pixels.readCount);
    }

    private static int bakeTriangle(CapturedSprite sprite) {
        OpacityMicromapData.Builder builder = new OpacityMicromapData.Builder();
        builder.addTriangle(sprite, uv(0.0F, 0.0F), uv(1.0F, 0.0F), uv(0.0F, 1.0F));
        return builder.build().triangleIndices()[0];
    }

    private static void assertAdaptiveTwoState(int width, int height, int expectedLevel) {
        CapturedSprite sprite = patternedSprite(
                "adaptive_" + width + "x" + height, width, height);
        OpacityMicromapData.Builder builder = new OpacityMicromapData.Builder();
        builder.addTriangle(sprite, uv(0.0F, 0.0F), uv(1.0F, 0.0F), uv(0.0F, 1.0F));
        OpacityMicromapData data = builder.build();
        assertEquals(1, data.blockCount());
        assertEquals(OpacityMicromapData.TWO_STATE_FORMAT, data.blockFormats()[0]);
        assertEquals(expectedLevel, data.blockSubdivisionLevels()[0]);
    }

    private static CapturedSprite patternedSprite(String path, int width, int height) {
        return patternedSprite(path, width, height, 0.0F, 0.0F, 1.0F, 1.0F);
    }

    private static CapturedSprite patternedSprite(
            String path,
            int width,
            int height,
            float u0,
            float v0,
            float u1,
            float v1) {
        int[] pixels = new int[Math.multiplyExact(width, height)];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[x + y * width] = ((x ^ y) & 1) == 0 ? 0xffff_ffff : 0;
            }
        }
        return sprite(
                path,
                u0,
                v0,
                u1,
                v1,
                width,
                height,
                false,
                new int[] {0},
                new ArrayPixels(pixels, width, height));
    }

    private static CpuClusterMesh translate(
            CapturedSprite sprite, CapturedSectionGeometry.Layer layer) {
        SectionMeshAccumulator.Quad source = SectionMeshAccumulatorTest.horizontalQuad(
                0.0F, 0.0F, 0.0F, 1.0F);
        CapturedSectionGeometry.MutableQuad quad = new CapturedSectionGeometry.MutableQuad();
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = source.x[vertex];
            quad.y[vertex] = source.y[vertex];
            quad.z[vertex] = source.z[vertex];
            quad.u[vertex] = source.u[vertex];
            quad.v[vertex] = source.v[vertex];
        }
        quad.normalX = source.normalX;
        quad.normalY = source.normalY;
        quad.normalZ = source.normalZ;
        CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
        section.add(
                quad,
                CapturedSectionGeometry.Surface.uniform(
                        -1,
                        layer,
                        (sprite.animated() ? CapturedSectionGeometry.Surface.ANIMATED : 0)
                                | CapturedSectionGeometry.Surface.MERGEABLE,
                        0,
                        sprite));
        CapturedCluster.Builder cluster = new CapturedCluster.Builder(0, 0, 0);
        cluster.add(0, 0, 0, section.build());
        return ClusterSceneTranslator.translate(
                cluster.build(),
                LabPbrMaterialSet.EMPTY,
                new ClusterTranslationSettings(
                        true,
                        TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                        OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                        true,
                        VoxelSurfaceSettings.BASE_HEIGHT));
    }

    private static CapturedSprite sprite(
            String path,
            float u0,
            float v0,
            float u1,
            float v1,
            boolean animated,
            int[] frames,
            SpritePixelView pixels) {
        return sprite(
                path, u0, v0, u1, v1, 16, 16, animated, frames, pixels);
    }

    private static CapturedSprite sprite(
            String path,
            float u0,
            float v0,
            float u1,
            float v1,
            int width,
            int height,
            boolean animated,
            int[] frames,
            SpritePixelView pixels) {
        return new CapturedSprite(
                new SpriteId("fixture", path),
                1,
                width,
                height,
                animated,
                frames,
                pixels);
    }

    private static int uv(float u, float v) {
        return PrimitivePacking.packUv(u, v);
    }

    private record ArrayPixels(int[] pixels, int imageWidth, int imageHeight)
            implements SpritePixelView {
        @Override
        public int argb(int x, int y) {
            return this.pixels[x + y * this.imageWidth];
        }
    }

    private static final class FailingPixels implements SpritePixelView {
        @Override
        public int imageWidth() {
            return 16;
        }

        @Override
        public int imageHeight() {
            return 16;
        }

        @Override
        public int argb(int x, int y) {
            throw new PixelFailure();
        }
    }

    private static final class GuardedPixels implements SpritePixelView {
        private boolean active = true;
        private int readCount;

        @Override
        public int imageWidth() {
            return 16;
        }

        @Override
        public int imageHeight() {
            return 16;
        }

        @Override
        public int argb(int x, int y) {
            if (!this.active) {
                throw new AssertionError("Borrowed pixels were read after the resource lease");
            }
            this.readCount++;
            return 0xffff_ffff;
        }
    }

    private static final class PixelFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
