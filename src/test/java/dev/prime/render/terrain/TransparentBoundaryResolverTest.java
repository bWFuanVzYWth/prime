package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.scene.CapturedSectionGeometry;
import org.junit.jupiter.api.Test;

final class TransparentBoundaryResolverTest {
    @Test
    void collisionBackedPaneIsSolidEvenWhenItsCapturedComponentIsOpen() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                new SectionMeshAccumulatorTest.TestSprite("solid_glass_pane")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFaceAt(0.5F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, -1, false, false, 0, 0, 0));
            CapturedSectionGeometry geometry = section.build();
            CapturedCluster.Builder captured = new CapturedCluster.Builder(0, 0, 0);
            captured.add(0, 0, 0, geometry);

            TransmissiveTopologyResolver.Result result =
                    TransmissiveTopologyResolver.resolve(captured.build());

            assertEquals(
                    TransmissiveTopology.SOLID,
                    result.topology(geometry.quads().getFirst()));
            assertTrue(result.issues().isEmpty());
        }
    }

    @Test
    void exactCoplanarReversePairIsTheOnlyThinSheetProof() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                new SectionMeshAccumulatorTest.TestSprite("proved_thin_sheet")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFaceAt(0.5F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, -1, true, false, 0, 0, 0));
            section.add(
                    xFaceAt(0.5F, -1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, -1, true, false, 0, 0, 0));
            CapturedSectionGeometry geometry = section.build();
            CapturedCluster.Builder captured = new CapturedCluster.Builder(0, 0, 0);
            captured.add(0, 0, 0, geometry);

            TransmissiveTopologyResolver.Result result =
                    TransmissiveTopologyResolver.resolve(captured.build());

            assertEquals(
                    TransmissiveTopology.THIN_SHEET,
                    result.topology(geometry.quads().get(0)));
            assertEquals(
                    TransmissiveTopology.THIN_SHEET,
                    result.topology(geometry.quads().get(1)));
            assertTrue(result.issues().isEmpty());
        }
    }

    @Test
    void exactClosedCollisionEmptyShellIsSolid() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                new SectionMeshAccumulatorTest.TestSprite("proved_closed_shell")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            CapturedSectionGeometry.Surface surface =
                    surface(glass, -1, true, false, 0, 0, 0);
            section.add(xFaceAt(0.0F, -1.0F, 0.0F, 1.0F, 0.0F, 1.0F), surface);
            section.add(xFaceAt(1.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F), surface);
            section.add(yFaceAt(0.0F, -1.0F), surface);
            section.add(yFaceAt(1.0F, 1.0F), surface);
            section.add(zFaceAt(0.0F, -1.0F), surface);
            section.add(zFaceAt(1.0F, 1.0F), surface);
            CapturedSectionGeometry geometry = section.build();
            CapturedCluster.Builder captured = new CapturedCluster.Builder(0, 0, 0);
            captured.add(0, 0, 0, geometry);

            TransmissiveTopologyResolver.Result result =
                    TransmissiveTopologyResolver.resolve(captured.build());

            for (CapturedSectionGeometry.Quad quad : geometry.quads()) {
                assertEquals(TransmissiveTopology.SOLID, result.topology(quad));
            }
            assertTrue(result.issues().isEmpty());
        }
    }

    @Test
    void ambiguousOpenShellIsOmittedAndReportedOncePerTexture() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                new SectionMeshAccumulatorTest.TestSprite("ambiguous_open_shell")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            CapturedSectionGeometry.Surface surface =
                    surface(glass, -1, true, false, 0, 0, 0);
            section.add(xFaceAt(0.0F, -1.0F, 0.0F, 1.0F, 0.0F, 1.0F), surface);
            section.add(yFaceAt(0.0F, -1.0F), surface);
            CapturedSectionGeometry geometry = section.build();
            CapturedCluster.Builder captured = new CapturedCluster.Builder(0, 0, 0);
            captured.add(0, 0, 0, geometry);
            CapturedCluster cluster = captured.build();

            TransmissiveTopologyResolver.Result result =
                    TransmissiveTopologyResolver.resolve(cluster);
            CpuClusterMesh mesh = translate(cluster);

            assertNull(result.topology(geometry.quads().get(0)));
            assertNull(result.topology(geometry.quads().get(1)));
            assertEquals(1, result.issues().size());
            assertEquals(result.issues(), mesh.compatibilityIssues());
            assertEquals(0L, mesh.triangleCount());
        }
    }

    @Test
    void equalSolidMediaRemoveTheSharedFace() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                new SectionMeshAccumulatorTest.TestSprite("equal_contact_glass")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, 0xff80_c0e0, false, false, 0, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, 0xff80_c0e0, false, false, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(0L, mesh.triangleCount());
            assertEquals(0L, mesh.surfaceRelationBytes());
        }
    }

    @Test
    void differentSolidMediaBecomeOneBilateralBoundary() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("contact_glass");
                SectionMeshAccumulatorTest.TestSprite ice =
                        new SectionMeshAccumulatorTest.TestSprite("contact_ice")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, 0xff40_80c0, false, false, 0, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(ice, 0xffc0_e8ff, false, false, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(2L, mesh.transmissiveTriangleCount());
            assertEquals(0L, mesh.opaqueTriangleCount());
            assertEquals(48L, mesh.surfaceRelationBytes());
            int[] records = mesh.segments().getFirst().surfaceRelationRecords();
            assertEquals(12, records.length);
            assertEquals(2, records[0]);
            assertEquals(7, records[1]);
            int primaryMedium = mesh.segments().getFirst()
                    .primitiveRecords()[PrimitivePacking.MEDIUM_ID_WORD];
            assertTrue(primaryMedium != 0);
            assertTrue(records[6] != 0);
            assertTrue(primaryMedium != records[6]);
            assertEquals(2, mesh.mediumCatalog().size());
            assertEquals(
                    CpuSectionMesh.SURFACE_RELATION_BOUNDARY,
                    records[2] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK);
            assertEquals(
                    CpuSectionMesh.SURFACE_RELATION_BOUNDARY,
                    records[7] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK);
            CompiledCluster decoded = CompiledClusterCodec.decode(
                    CompiledClusterCodec.encode(
                            new CompiledCluster(0L, 0, 0, 0, mesh)));
            assertEquals(48L, decoded.mesh().surfaceRelationBytes());
            assertEquals(mesh.mediumCatalog(), decoded.mesh().mediumCatalog());
            assertArrayEquals(
                    records,
                    decoded.mesh().segments().getFirst().surfaceRelationRecords());
        }
    }

    @Test
    void solidMediumOwnsThePartialPaneContact() {
        try (SectionMeshAccumulatorTest.TestSprite pane =
                        new SectionMeshAccumulatorTest.TestSprite("contact_pane");
                SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("contact_block")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.25F, 0.75F, 0.375F, 0.625F),
                    surface(pane, 0xffa0_d0f0, false, false, 0, 0, 0, 1));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, 0xffa0_d0f0, false, false, 1, 0, 0, 1));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(0.875F, projectedArea(mesh), 1.0E-6F);
            assertEquals(0L, mesh.surfaceRelationBytes());
        }
    }

    @Test
    void globalRelationSizeIncludesRelationFreeSegments() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("segmented_contact_glass");
                SectionMeshAccumulatorTest.TestSprite ice =
                        new SectionMeshAccumulatorTest.TestSprite("segmented_contact_ice");
                SectionMeshAccumulatorTest.TestSprite stone =
                        new SectionMeshAccumulatorTest.TestSprite("segmented_contact_stone")) {
            CapturedSectionGeometry.Builder boundary =
                    new CapturedSectionGeometry.Builder();
            boundary.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, 0xff40_80c0, false, false, 0, 0, 0));
            boundary.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(ice, 0xffc0_e8ff, false, false, 1, 0, 0));
            CapturedSectionGeometry.Builder ordinary =
                    new CapturedSectionGeometry.Builder();
            ordinary.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(stone, -1, false, true, 16, 0, 0));
            CapturedCluster.Builder captured = new CapturedCluster.Builder(0, 0, 0);
            captured.add(0, 0, 0, boundary.build());
            captured.add(1, 0, 0, ordinary.build());

            CpuClusterMesh mesh = translate(captured.build(), 2);

            assertEquals(2, mesh.segments().size());
            assertEquals(0, mesh.segments().get(1).surfaceRelationRecords().length);
            int[] globalRecords = mesh.surfaceRelationRecords();
            assertEquals(
                    (long) globalRecords.length * Integer.BYTES,
                    mesh.surfaceRelationBytes());
            assertTrue(mesh.surfaceRelationBytes()
                    > (long) mesh.segments().getFirst().surfaceRelationRecords().length
                            * Integer.BYTES);
        }
    }

    @Test
    void opaqueSurfaceWinsOverTheCoincidentGlassFace() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("contact_transparent");
                SectionMeshAccumulatorTest.TestSprite stone =
                        new SectionMeshAccumulatorTest.TestSprite("contact_opaque")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, -1, false, false, 0, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(stone, -1, false, true, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(2L, mesh.opaqueTriangleCount());
            assertEquals(0L, mesh.transmissiveTriangleCount());
        }
    }

    @Test
    void layeredOpaqueSurfaceWinsWithoutDroppingItsOverlay() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("layered_transparent");
                SectionMeshAccumulatorTest.TestSprite stone =
                        new SectionMeshAccumulatorTest.TestSprite("layered_opaque");
                SectionMeshAccumulatorTest.TestSprite overlay =
                        new SectionMeshAccumulatorTest.TestSprite("layered_overlay")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, -1, false, false, 0, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(stone, -1, false, true, 1, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    cutoutSurface(overlay, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(2L, mesh.opaqueTriangleCount());
            assertEquals(2L, mesh.cutoutTriangleCount());
            assertEquals(0L, mesh.transmissiveTriangleCount());
        }
    }

    @Test
    void subpixelWallFireBecomesAPositiveSideOverlay() {
        try (SectionMeshAccumulatorTest.TestSprite stone =
                        new SectionMeshAccumulatorTest.TestSprite("wall_fire_stone");
                SectionMeshAccumulatorTest.TestSprite fire =
                        new SectionMeshAccumulatorTest.TestSprite("wall_fire_layer")) {
            stone.fill(0xff70_7070);
            fire.fill(0xffff_8020);
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            section.add(
                    xFaceAt(1.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(stone, -1, false, true, 0, 0, 0));
            section.add(
                    xFaceAt(1.000625F, -1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    emissiveOverlay(fire, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(2L, mesh.opaqueTriangleCount());
            assertEquals(0L, mesh.cutoutTriangleCount());
            CpuClusterMesh.Segment segment = mesh.segments().getFirst();
            int[] relation = SurfaceRelationTable.record(
                    segment.surfaceRelationRecords(),
                    segment.opaquePrimitiveCount(),
                    0);
            assertEquals(
                    CpuSectionMesh.SURFACE_RELATION_OVERLAY,
                    relation[0] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK);
            assertTrue((relation[0]
                    & CpuSectionMesh.SURFACE_RELATION_POSITIVE_ONLY) != 0);
            assertTrue((relation[0] >> 8
                    & PrimitivePacking.CONTROL_ALPHA_CUTOUT) != 0);
            assertEquals(1.0F, segment.positions()[0], 0.0F);
        }
    }

    @Test
    void equalFluidMediaUseTheSameContactResolver() {
        try (SectionMeshAccumulatorTest.TestSprite water =
                new SectionMeshAccumulatorTest.TestSprite("fluid_contact_water")) {
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    fluidSurface(water, 0, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    fluidSurface(water, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(0L, mesh.triangleCount());
            assertEquals(0L, mesh.surfaceRelationBytes());
        }
    }

    @Test
    void adjacentCutoutFacesBecomeOneDirectionalSheet() {
        try (SectionMeshAccumulatorTest.TestSprite first =
                        new SectionMeshAccumulatorTest.TestSprite("adjacent_cutout_first");
                SectionMeshAccumulatorTest.TestSprite second =
                        new SectionMeshAccumulatorTest.TestSprite("adjacent_cutout_second")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFace(1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    cutoutSurface(first, 0, 0, 0));
            section.add(
                    xFace(-1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    cutoutSurface(second, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(2L, mesh.cutoutTriangleCount());
            assertEquals(0L, mesh.opaqueTriangleCount());
            int[] table = mesh.segments().getFirst().surfaceRelationRecords();
            assertEquals(2, table[0]);
            assertEquals(
                    CpuSectionMesh.SURFACE_RELATION_BILATERAL,
                    table[2] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK);
        }
    }

    @Test
    void knownFluidSideInsetBecomesTheExactGlassBoundary() {
        try (SectionMeshAccumulatorTest.TestSprite water =
                        new SectionMeshAccumulatorTest.TestSprite("inset_water");
                SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("inset_glass")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFaceAt(0.999F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    fluidSurface(water, 0, 0, 0));
            section.add(
                    xFaceAt(1.0F, -1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, 0xffb0_d8f0, false, false, 1, 0, 0, 9));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(2L, mesh.transmissiveTriangleCount());
            assertEquals(48L, mesh.surfaceRelationBytes());
            assertEquals(1.0F, mesh.segments().getFirst().positions()[0], 0.0F);
        }
    }

    @Test
    void knownFluidSideInsetCompositesCutoutCoverageOverWater() {
        try (SectionMeshAccumulatorTest.TestSprite water =
                        new SectionMeshAccumulatorTest.TestSprite("inset_leaf_water");
                SectionMeshAccumulatorTest.TestSprite leaves =
                        new SectionMeshAccumulatorTest.TestSprite("inset_leaves")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFaceAt(0.999F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    fluidSurface(water, 0, 0, 0));
            section.add(
                    xFaceAt(1.0F, -1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    cutoutSurface(leaves, 1, 0, 0));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(2L, mesh.cutoutTriangleCount());
            assertEquals(0L, mesh.transmissiveTriangleCount());
            CpuClusterMesh.Segment segment = mesh.segments().getFirst();
            int[] relation = SurfaceRelationTable.record(
                    segment.surfaceRelationRecords(), segment.cutoutPrimitiveCount(), 0);
            assertEquals(
                    CpuSectionMesh.SURFACE_RELATION_OVERLAY,
                    relation[0] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK);
            int secondaryFlags = PrimitivePacking.unpackControl(relation[4], relation[6]);
            assertTrue(PrimitivePacking.isTransmissive(secondaryFlags));
            assertEquals(1.0F, segment.positions()[0], 0.0F);
        }
    }

    @Test
    void arbitraryFluidGapIsNotSnapped() {
        try (SectionMeshAccumulatorTest.TestSprite water =
                        new SectionMeshAccumulatorTest.TestSprite("unsnapped_water");
                SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("unsnapped_glass")) {
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(
                    xFaceAt(0.9985F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    fluidSurface(water, 0, 0, 0));
            section.add(
                    xFaceAt(1.0F, -1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, 0xffb0_d8f0, false, false, 1, 0, 0, 9));

            CpuClusterMesh mesh = translate(0, section.build());

            assertEquals(4L, mesh.transmissiveTriangleCount());
            assertEquals(0L, mesh.surfaceRelationBytes());
        }
    }

    @Test
    void clusterHaloUsesTheLowerBlockAsTheOnlyOwner() {
        try (SectionMeshAccumulatorTest.TestSprite glass =
                        new SectionMeshAccumulatorTest.TestSprite("halo_glass", 1);
                SectionMeshAccumulatorTest.TestSprite ice =
                        new SectionMeshAccumulatorTest.TestSprite("halo_ice", 2)) {
            CapturedSectionGeometry.Builder lowerSection =
                    new CapturedSectionGeometry.Builder();
            lowerSection.add(
                    xFaceAt(16.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, -1, false, false, 63, 0, 0));
            lowerSection.addPeer(
                    xFaceAt(16.0F, -1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(ice, -1, false, false, 64, 0, 0));
            CapturedCluster.Builder lower = new CapturedCluster.Builder(0, 0, 0);
            lower.add(3, 0, 0, lowerSection.build());

            CapturedSectionGeometry.Builder upperSection =
                    new CapturedSectionGeometry.Builder();
            upperSection.add(
                    xFaceAt(0.0F, -1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(ice, -1, false, false, 64, 0, 0));
            upperSection.addPeer(
                    xFaceAt(0.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F),
                    surface(glass, -1, false, false, 63, 0, 0));
            CapturedCluster.Builder upper = new CapturedCluster.Builder(4, 0, 0);
            upper.add(4, 0, 0, upperSection.build());

            CpuClusterMesh lowerMesh = translate(lower.build());
            CpuClusterMesh upperMesh = translate(upper.build());

            assertEquals(2L, lowerMesh.transmissiveTriangleCount());
            assertEquals(48L, lowerMesh.surfaceRelationBytes());
            assertEquals(0L, upperMesh.triangleCount());
        }
    }

    private static CapturedSectionGeometry.Surface surface(
            SectionMeshAccumulatorTest.TestSprite sprite,
            int color,
            boolean collisionEmpty,
            boolean opaque,
            int x,
            int y,
            int z) {
        return surface(sprite, color, collisionEmpty, opaque, x, y, z, 0);
    }

    private static CapturedSectionGeometry.Surface surface(
            SectionMeshAccumulatorTest.TestSprite sprite,
            int color,
            boolean collisionEmpty,
            boolean opaque,
            int x,
            int y,
            int z,
            int mediumFamily) {
        return CapturedSectionGeometry.Surface.uniform(
                color,
                opaque
                        ? CapturedSectionGeometry.Layer.OPAQUE
                        : CapturedSectionGeometry.Layer.TRANSLUCENT,
                false,
                collisionEmpty,
                false,
                false,
                false,
                true,
                false,
                0,
                sprite.sprite(),
                new CapturedSectionGeometry.BlockFacts(x, y, z, mediumFamily));
    }

    private static CapturedSectionGeometry.Surface cutoutSurface(
            SectionMeshAccumulatorTest.TestSprite sprite, int x, int y, int z) {
        return CapturedSectionGeometry.Surface.uniform(
                -1,
                CapturedSectionGeometry.Layer.CUTOUT,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                0,
                sprite.sprite(),
                new CapturedSectionGeometry.BlockFacts(x, y, z));
    }

    private static CapturedSectionGeometry.Surface emissiveOverlay(
            SectionMeshAccumulatorTest.TestSprite sprite, int x, int y, int z) {
        return CapturedSectionGeometry.Surface.uniform(
                -1,
                CapturedSectionGeometry.Layer.CUTOUT,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                15,
                sprite.sprite(),
                new CapturedSectionGeometry.BlockFacts(x, y, z));
    }

    private static CapturedSectionGeometry.Surface fluidSurface(
            SectionMeshAccumulatorTest.TestSprite sprite, int x, int y, int z) {
        return new CapturedSectionGeometry.Surface(
                -1,
                -1,
                -1,
                -1,
                CapturedSectionGeometry.Layer.TRANSLUCENT,
                false,
                false,
                true,
                true,
                false,
                false,
                false,
                0,
                sprite.sprite(),
                new CapturedSectionGeometry.FluidFacts(x, y, z, false, 0),
                new CapturedSectionGeometry.BlockFacts(x, y, z));
    }

    private static float projectedArea(CpuClusterMesh mesh) {
        float area = 0.0F;
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            float[] positions = segment.positions();
            int first = 9 * (segment.opaqueTriangleCount() + segment.cutoutTriangleCount());
            for (int offset = first; offset < positions.length; offset += 9) {
                float edgeOneY = positions[offset + 4] - positions[offset + 1];
                float edgeOneZ = positions[offset + 5] - positions[offset + 2];
                float edgeTwoY = positions[offset + 7] - positions[offset + 1];
                float edgeTwoZ = positions[offset + 8] - positions[offset + 2];
                area += 0.5F * Math.abs(
                        edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY);
            }
        }
        return area;
    }

    private static CapturedSectionGeometry.MutableQuad xFace(
            float normal,
            float minimumY,
            float maximumY,
            float minimumZ,
            float maximumZ) {
        return xFaceAt(
                1.0F,
                normal,
                minimumY,
                maximumY,
                minimumZ,
                maximumZ);
    }

    private static CapturedSectionGeometry.MutableQuad xFaceAt(
            float plane,
            float normal,
            float minimumY,
            float maximumY,
            float minimumZ,
            float maximumZ) {
        CapturedSectionGeometry.MutableQuad quad =
                new CapturedSectionGeometry.MutableQuad();
        float[] y = normal > 0.0F
                ? new float[] {minimumY, maximumY, maximumY, minimumY}
                : new float[] {minimumY, minimumY, maximumY, maximumY};
        float[] z = normal > 0.0F
                ? new float[] {minimumZ, minimumZ, maximumZ, maximumZ}
                : new float[] {minimumZ, maximumZ, maximumZ, minimumZ};
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = plane;
            quad.y[vertex] = y[vertex];
            quad.z[vertex] = z[vertex];
            quad.u[vertex] = (vertex == 1 || vertex == 2) ? 1.0F : 0.0F;
            quad.v[vertex] = vertex >= 2 ? 1.0F : 0.0F;
        }
        quad.normalX = normal;
        return quad;
    }

    private static CapturedSectionGeometry.MutableQuad yFaceAt(float plane, float normal) {
        CapturedSectionGeometry.MutableQuad quad =
                new CapturedSectionGeometry.MutableQuad();
        float[] x = normal > 0.0F
                ? new float[] {0.0F, 0.0F, 1.0F, 1.0F}
                : new float[] {0.0F, 1.0F, 1.0F, 0.0F};
        float[] z = normal > 0.0F
                ? new float[] {0.0F, 1.0F, 1.0F, 0.0F}
                : new float[] {0.0F, 0.0F, 1.0F, 1.0F};
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = x[vertex];
            quad.y[vertex] = plane;
            quad.z[vertex] = z[vertex];
            quad.u[vertex] = x[vertex];
            quad.v[vertex] = z[vertex];
        }
        quad.normalY = normal;
        return quad;
    }

    private static CapturedSectionGeometry.MutableQuad zFaceAt(float plane, float normal) {
        CapturedSectionGeometry.MutableQuad quad =
                new CapturedSectionGeometry.MutableQuad();
        float[] x = normal > 0.0F
                ? new float[] {0.0F, 1.0F, 1.0F, 0.0F}
                : new float[] {0.0F, 0.0F, 1.0F, 1.0F};
        float[] y = normal > 0.0F
                ? new float[] {0.0F, 0.0F, 1.0F, 1.0F}
                : new float[] {0.0F, 1.0F, 1.0F, 0.0F};
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = x[vertex];
            quad.y[vertex] = y[vertex];
            quad.z[vertex] = plane;
            quad.u[vertex] = x[vertex];
            quad.v[vertex] = y[vertex];
        }
        quad.normalZ = normal;
        return quad;
    }

    private static CpuClusterMesh translate(
            int sectionX, CapturedSectionGeometry section) {
        CapturedCluster.Builder captured = new CapturedCluster.Builder(0, 0, 0);
        captured.add(sectionX, 0, 0, section);
        return translate(captured.build());
    }

    private static CpuClusterMesh translate(CapturedCluster captured) {
        return translate(captured, TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES);
    }

    private static CpuClusterMesh translate(
            CapturedCluster captured, int segmentTriangleTarget) {
        return ClusterSceneTranslator.translate(
                captured,
                LabPbrMaterialSet.EMPTY,
                new ClusterTranslationSettings(
                        false,
                        segmentTriangleTarget,
                        OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                        false,
                        VoxelSurfaceSettings.BASE_HEIGHT,
                        false,
                        false));
    }
}
