package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SectionClusterMeshBuilderTest {
    @Test
    void keepsOpaqueBeforeCutoutBeforeTransmissionAndTranslatesSectionLocalPositions() {
        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(-4, 0, 8);
        builder.add(-4, 0, 8, List.of(mesh(1.0F, 1, 1, 1)));
        builder.add(-1, 2, 11, List.of(mesh(2.0F, 1, 1, 1)));

        CpuClusterMesh cluster = builder.build();
        assertEquals(1, cluster.segments().size());
        CpuClusterMesh.Segment result = cluster.segments().getFirst();
        assertEquals(2, result.opaqueTriangleCount());
        assertEquals(2, result.cutoutTriangleCount());
        assertEquals(2, result.transmissiveTriangleCount());
        assertArrayEquals(new float[] {
            1, 1, 1, 2, 1, 1, 1, 2, 1,
            50, 34, 50, 51, 34, 50, 50, 35, 50,
            1, 1, 2, 2, 1, 2, 1, 2, 2,
            50, 34, 51, 51, 34, 51, 50, 35, 51,
            1, 1, 3, 2, 1, 3, 1, 2, 3,
            50, 34, 52, 51, 34, 52, 50, 35, 52
        }, result.positions());
        assertEquals(1, result.primitiveRecords()[0]);
        assertEquals(2, result.primitiveRecords()[8]);
        assertEquals(101, result.primitiveRecords()[16]);
        assertEquals(102, result.primitiveRecords()[24]);
        assertEquals(201, result.primitiveRecords()[32]);
        assertEquals(202, result.primitiveRecords()[40]);
    }

    @Test
    void rejectsDuplicateSectionsInsideOneVirtualChunk() {
        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(0, 0, 0);
        builder.add(0, 0, 0, List.of(mesh(0.0F, 1, 1, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.add(0, 0, 0, List.of(mesh(0.0F, 1, 1, 1))));
    }

    @Test
    void segmentsLargeLogicalClustersWithoutCreatingAnotherLogicalPayload() {
        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(0, 0, 0);
        int trianglesPerSection = 60_000;
        int sectionCount = 17;
        CpuSectionMesh section = opaqueMesh(trianglesPerSection);
        for (int index = 0; index < sectionCount; index++) {
            builder.add(
                    index & 3,
                    index >> 2 & 3,
                    index >> 4,
                    List.of(section));
        }

        CpuClusterMesh cluster = builder.build();

        assertEquals(9, cluster.segments().size());
        assertEquals(
                (long) trianglesPerSection * sectionCount,
                cluster.triangleCount());
        assertEquals(
                section.byteSize() * sectionCount,
                cluster.positionBytes() + cluster.primitiveBytes());
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void mergesCompatibleFacesAcrossSectionBoundaries() {
        TestSprite sprite = new TestSprite();
        TestSprite equivalentSprite = new TestSprite();
        SectionMeshAccumulator left = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        left.addQuad(
                SectionMeshAccumulatorTest.horizontalQuad(15.0F, 2.0F, 3.0F, 1.0F),
                SectionMeshAccumulatorTest.opaqueSurface(sprite));
        SectionMeshAccumulator right = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        right.addQuad(
                SectionMeshAccumulatorTest.horizontalQuad(0.0F, 2.0F, 3.0F, 1.0F),
                SectionMeshAccumulatorTest.opaqueSurface(equivalentSprite));

        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(0, 0, 0);
        builder.add(0, 0, 0, left.build());
        builder.add(1, 0, 0, right.build());
        CpuClusterMesh cluster = builder.build();

        assertEquals(2, cluster.triangleCount());
        CpuClusterMesh.Segment segment = cluster.segments().getFirst();
        assertEquals(2, segment.opaqueTriangleCount());
        assertEquals(2, segment.opaqueMacroTriangleCount());
        assertEquals(CpuSectionMesh.PRIMITIVE_WORDS, segment.primitiveRecords().length);
        assertEquals(32L, cluster.primitiveBytes());
        assertEquals(0L, cluster.opaqueMacroTriangleBase());
        assertArrayEquals(
                new float[] {
                    15, 2, 3, 17, 2, 3, 17, 3, 3,
                    15, 2, 3, 17, 3, 3, 15, 3, 3
                },
                cluster.segments().getFirst().positions());
    }

    @Test
    void keepsOrdinaryRecordsBeforeSharedMacroRecords() {
        TestSprite sprite = new TestSprite();
        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(0, 0, 0);
        builder.add(0, 0, 0, List.of(opaqueMesh(1)));
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        accumulator.addQuad(
                SectionMeshAccumulatorTest.horizontalQuad(0.0F, 0.0F, 1.0F, 1.0F),
                SectionMeshAccumulatorTest.opaqueSurface(sprite));
        builder.add(1, 0, 0, accumulator.build());

        CpuClusterMesh cluster = builder.build();
        CpuClusterMesh.Segment segment = cluster.segments().getFirst();

        assertEquals(3, segment.opaqueTriangleCount());
        assertEquals(2, segment.opaqueMacroTriangleCount());
        assertEquals(2, segment.opaquePrimitiveCount());
        assertEquals(1L, cluster.opaqueMacroTriangleBase());
        assertEquals(2L * 32L, cluster.primitiveBytes());
    }

    @Test
    void mergesPlanesByTheirExactSixteenthBlockBucket() {
        TestSprite sprite = new TestSprite();
        float plane = 49.0F / 16.0F;
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        accumulator.addQuad(
                SectionMeshAccumulatorTest.horizontalQuad(0.0F, 0.0F, plane, 1.0F),
                SectionMeshAccumulatorTest.opaqueSurface(sprite));
        accumulator.addQuad(
                SectionMeshAccumulatorTest.horizontalQuad(
                        1.0F, 0.0F, Math.nextDown(plane), 1.0F),
                SectionMeshAccumulatorTest.opaqueSurface(sprite));

        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(0, 0, 0);
        builder.add(0, 0, 0, accumulator.build());
        CpuClusterMesh cluster = builder.build();

        assertEquals(2, cluster.triangleCount());
        assertArrayEquals(
                new float[] {
                    0, 0, plane, 2, 0, plane, 2, 1, plane,
                    0, 0, plane, 2, 1, plane, 0, 1, plane
                },
                cluster.segments().getFirst().positions());
    }

    @Test
    void coversCutoutFacesOnlyWithBoundedSquareTemplates() {
        TestSprite sprite = new TestSprite();
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                accumulator.addQuad(
                        SectionMeshAccumulatorTest.horizontalQuad(x, y, 1.0F, 1.0F),
                        SectionMeshAccumulatorTest.cutoutSurface(sprite));
            }
        }

        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(0, 0, 0);
        builder.add(0, 0, 0, accumulator.build());
        CpuClusterMesh cluster = builder.build();

        assertEquals(2, cluster.triangleCount());
        assertEquals(2, cluster.segments().getFirst().cutoutTriangleCount());
        assertEquals(2, cluster.segments().getFirst().cutoutMacroTriangleCount());
        assertEquals(
                2,
                cluster.opacityMicromap().triangleIndices().length);
    }

    @Test
    void neverMergesDifferentMaterialOrUvMappings() {
        TestSprite sprite = new TestSprite();
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        accumulator.addQuad(
                SectionMeshAccumulatorTest.horizontalQuad(0.0F, 0.0F, 2.0F, 1.0F),
                SectionMeshAccumulatorTest.opaqueSurface(sprite));
        SectionMeshAccumulator.Quad tinted =
                SectionMeshAccumulatorTest.horizontalQuad(1.0F, 0.0F, 2.0F, 1.0F);
        accumulator.addQuad(
                tinted,
                new SectionMeshAccumulator.Surface().set(
                        CapturedSectionGeometry.Surface.uniform(
                                0xffff0000,
                                CapturedSectionGeometry.Layer.OPAQUE,
                                CapturedSectionGeometry.Surface.MERGEABLE,
                                0,
                                sprite.sprite()),
                        TransmissiveTopology.NONE,
                        0));
        SectionMeshAccumulator.Quad rotated =
                SectionMeshAccumulatorTest.horizontalQuad(2.0F, 0.0F, 2.0F, 1.0F);
        for (int vertex = 0; vertex < 4; vertex++) {
            float oldU = rotated.u[vertex];
            rotated.u[vertex] = rotated.v[vertex];
            rotated.v[vertex] = 1.0F - oldU;
        }
        accumulator.addQuad(
                rotated, SectionMeshAccumulatorTest.opaqueSurface(sprite));

        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(0, 0, 0);
        builder.add(0, 0, 0, accumulator.build());
        CpuClusterMesh cluster = builder.build();

        assertEquals(6, cluster.triangleCount());
    }

    @Test
    void mergesAdjacentFacesWithTheSameSelectedTextureRotation() {
        TestSprite sprite = new TestSprite();
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        SectionMeshAccumulator.Quad first =
                SectionMeshAccumulatorTest.horizontalQuad(0.0F, 0.0F, 4.0F, 1.0F);
        SectionMeshAccumulator.Quad second =
                SectionMeshAccumulatorTest.horizontalQuad(1.0F, 0.0F, 4.0F, 1.0F);
        rotateUv(first);
        rotateUv(second);
        accumulator.addQuad(first, SectionMeshAccumulatorTest.opaqueSurface(sprite));
        accumulator.addQuad(second, SectionMeshAccumulatorTest.opaqueSurface(sprite));

        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(0, 0, 0);
        builder.add(0, 0, 0, accumulator.build());

        assertEquals(2, builder.build().triangleCount());
    }

    @Test
    void optimallyPartitionsShapeThatDefeatsRowGreedyCover() {
        TestSprite sprite = new TestSprite();
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        int[][] cells = {{1, 0}, {0, 1}, {1, 1}, {2, 1}};
        for (int[] cell : cells) {
            accumulator.addQuad(
                    SectionMeshAccumulatorTest.horizontalQuad(
                            cell[0], cell[1], 6.0F, 1.0F),
                    SectionMeshAccumulatorTest.opaqueSurface(sprite));
        }

        SectionClusterMeshBuilder builder =
                new SectionClusterMeshBuilder(0, 0, 0);
        builder.add(0, 0, 0, accumulator.build());
        CpuClusterMesh cluster = builder.build();

        assertEquals(4, cluster.triangleCount());
        assertEquals(4, cluster.opaqueTriangleCount());
    }

    @Test
    void keepsDuplicateMergeFacesAsSeparateUnitFaces() {
        TestSprite sprite = new TestSprite();
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        accumulator.addQuad(
                SectionMeshAccumulatorTest.horizontalQuad(
                        2.0F, 3.0F, 7.0F, 1.0F),
                SectionMeshAccumulatorTest.opaqueSurface(sprite));
        accumulator.addQuad(
                SectionMeshAccumulatorTest.horizontalQuad(
                        2.0F, 3.0F, 7.0F, 1.0F),
                SectionMeshAccumulatorTest.opaqueSurface(sprite));

        SectionClusterMeshBuilder builder =
                new SectionClusterMeshBuilder(0, 0, 0);
        builder.add(0, 0, 0, accumulator.build());
        CpuClusterMesh cluster = builder.build();

        assertEquals(4, cluster.triangleCount());
        assertEquals(4, cluster.opaqueTriangleCount());
    }

    @Test
    void mergesNonFluidTransmissiveFacesIntoTheTransmissiveGeometry() {
        TestSprite sprite = new TestSprite();
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        accumulator.addQuad(
                SectionMeshAccumulatorTest.horizontalQuad(0.0F, 0.0F, 5.0F, 1.0F),
                SectionMeshAccumulatorTest.transmissiveSurface(sprite, false));
        accumulator.addQuad(
                SectionMeshAccumulatorTest.horizontalQuad(1.0F, 0.0F, 5.0F, 1.0F),
                SectionMeshAccumulatorTest.transmissiveSurface(sprite, false));

        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(0, 0, 0);
        builder.add(0, 0, 0, accumulator.build());
        CpuClusterMesh cluster = builder.build();

        assertEquals(2, cluster.triangleCount());
        assertEquals(0, cluster.opaqueTriangleCount());
        assertEquals(0, cluster.cutoutTriangleCount());
        assertEquals(2, cluster.transmissiveTriangleCount());
        assertEquals(2, cluster.transmissiveMacroTriangleCount());
        int[] primitives = cluster.segments().getFirst().primitiveRecords();
        int flags = PrimitivePacking.unpackControl(primitives[3], primitives[5]);
        assertTrue(PrimitivePacking.isTransmissive(flags));
    }

    private static void rotateUv(SectionMeshAccumulator.Quad quad) {
        for (int vertex = 0; vertex < 4; vertex++) {
            float oldU = quad.u[vertex];
            quad.u[vertex] = quad.v[vertex];
            quad.v[vertex] = 1.0F - oldU;
        }
    }

    private static CpuSectionMesh mesh(float base, int opaque, int cutout, int transmissive) {
        float[] positions = new float[] {
            base, base, base,
            base + 1, base, base,
            base, base + 1, base,
            base, base, base + 1,
            base + 1, base, base + 1,
            base, base + 1, base + 1,
            base, base, base + 2,
            base + 1, base, base + 2,
            base, base + 1, base + 2
        };
        int[] primitives = new int[(opaque + cutout + transmissive) * CpuSectionMesh.PRIMITIVE_WORDS];
        primitives[0] = (int) base;
        primitives[opaque * CpuSectionMesh.PRIMITIVE_WORDS] = (int) base + 100;
        primitives[(opaque + cutout) * CpuSectionMesh.PRIMITIVE_WORDS] = (int) base + 200;
        return new CpuSectionMesh(
                positions,
                primitives,
                new int[0],
                TriangleLayout.triangles(opaque, cutout, transmissive),
                specialMicromap(cutout),
                CpuSectionLights.EMPTY);
    }

    private static OpacityMicromapData specialMicromap(int triangleCount) {
        return OpacityMicromapData.fullyUnknown(triangleCount);
    }

    private static CpuSectionMesh opaqueMesh(int triangleCount) {
        return new CpuSectionMesh(
                new float[Math.multiplyExact(triangleCount, 9)],
                new int[Math.multiplyExact(triangleCount, CpuSectionMesh.PRIMITIVE_WORDS)],
                new int[0],
                TriangleLayout.triangles(triangleCount, 0, 0),
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
    }
}
