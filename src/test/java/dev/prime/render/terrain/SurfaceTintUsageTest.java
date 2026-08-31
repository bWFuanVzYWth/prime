package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SurfaceTintUsageTest {
    @Test
    void disabledBuilderDoesNotRetainCaptureData() {
        try (SectionMeshAccumulatorTest.TestSprite sprite =
                new SectionMeshAccumulatorTest.TestSprite("surface_tint_disabled")) {
            SurfaceTintUsage.Builder builder = SurfaceTintUsage.builder(false);
            builder.addPrimary(surface(sprite, -1, -1, -1, -1));

            assertEquals(SurfaceTintUsage.EMPTY, builder.build());
        }
    }

    @Test
    void classifiesFourCornerRgbAndAlphaWithoutFaceAveraging() {
        try (SectionMeshAccumulatorTest.TestSprite sprite =
                new SectionMeshAccumulatorTest.TestSprite("surface_tint_usage")) {
            SurfaceTintUsage.Builder builder = SurfaceTintUsage.builder();
            builder.addPrimary(surface(
                    sprite,
                    0xff10_2030,
                    0xff10_2030,
                    0xff10_2030,
                    0xff10_2030));
            builder.addPrimary(surface(
                    sprite,
                    0xff10_2030,
                    0xff20_3040,
                    0xff10_2030,
                    0xff20_3040));
            builder.addRelation(surface(
                    sprite,
                    0x4010_2030,
                    0x8010_2030,
                    0x4010_2030,
                    0x8010_2030));

            SurfaceTintUsage usage = builder.build();

            assertEquals(2L, usage.primaryReferences());
            assertEquals(1L, usage.relationReferences());
            assertEquals(1L, usage.constantReferences());
            assertEquals(2L, usage.varyingReferences());
            assertEquals(1L, usage.varyingRgbReferences());
            assertEquals(1L, usage.varyingAlphaReferences());
            assertEquals(1L, usage.nonOpaqueAlphaReferences());
            assertEquals(4, usage.sourceColors().size());
            assertEquals(32L, usage.globalSamplePaletteRgba16fBytes());
            assertEquals(48L, usage.quadIndexedRgba16fBytes());
            assertEquals(56L, usage.triangleIndexedRgba16fBytes());
            assertEquals(64L, usage.quadSharedRgba16fBytes());
            assertEquals(96L, usage.triangleLocalRgba16fBytes());
        }
    }

    @Test
    void residentCombinationSumsCountsWhileObservedUnionKeepsPeaks() {
        try (SectionMeshAccumulatorTest.TestSprite sprite =
                new SectionMeshAccumulatorTest.TestSprite("surface_tint_union")) {
            SurfaceTintUsage.Builder firstBuilder = SurfaceTintUsage.builder();
            firstBuilder.addPrimary(surface(
                    sprite, -1, -1, -1, -1));
            SurfaceTintUsage.Builder secondBuilder = SurfaceTintUsage.builder();
            secondBuilder.addPrimary(surface(
                    sprite, 0xff00_0000, 0xff00_0010, 0xff00_0000, 0xff00_0010));
            SurfaceTintUsage first = firstBuilder.build();
            SurfaceTintUsage second = secondBuilder.build();

            SurfaceTintUsage resident = SurfaceTintUsage.combine(List.of(first, second));
            SurfaceTintUsage observed = first.observedUnion(second);

            assertEquals(2L, resident.primaryReferences());
            assertEquals(1L, resident.constantReferences());
            assertEquals(1L, resident.varyingReferences());
            assertEquals(1L, observed.primaryReferences());
            assertEquals(1L, observed.constantReferences());
            assertEquals(1L, observed.varyingReferences());
            assertEquals(3, observed.sourceColors().size());
        }
    }

    private static CapturedSectionGeometry.Surface surface(
            SectionMeshAccumulatorTest.TestSprite sprite,
            int color0,
            int color1,
            int color2,
            int color3) {
        return new CapturedSectionGeometry.Surface(
                color0,
                color1,
                color2,
                color3,
                CapturedSectionGeometry.Layer.OPAQUE,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                0,
                sprite.sprite(),
                null,
                null);
    }
}
