package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.math.Quadrant;
import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.scene.CapturedSprite;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.CuboidRotation;
import net.minecraft.client.resources.model.cuboid.FaceBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

final class ClusterSceneTranslatorTest {
    @Test
    void alphaCutOverrideIsResolvedOnlyInsideClusterTranslation() {
        try (SectionMeshAccumulatorTest.TestSprite sprite =
                new SectionMeshAccumulatorTest.TestSprite("alpha_cut_override")) {
            CapturedSectionGeometry.Surface translucent =
                    CapturedSectionGeometry.Surface.uniform(
                            -1,
                            CapturedSectionGeometry.Layer.TRANSLUCENT,
                            false,
                            false,
                            false,
                            false,
                            false,
                            true,
                            false,
                            0,
                            sprite.sprite());
            CapturedSectionGeometry.Surface alphaCut =
                    CapturedSectionGeometry.Surface.uniform(
                            -1,
                            CapturedSectionGeometry.Layer.TRANSLUCENT,
                            true,
                            false,
                            false,
                            false,
                            false,
                            true,
                            false,
                            0,
                            sprite.sprite());

            assertFalse(ClusterSceneTranslator.isCutout(translucent));
            assertTrue(ClusterSceneTranslator.isTransmissive(translucent));
            assertTrue(ClusterSceneTranslator.isCutout(alphaCut));
            assertFalse(ClusterSceneTranslator.isTransmissive(alphaCut));
        }
    }

    @Test
    void vanillaGrassSideOverlayIsCompositedWithoutMovingItsCapturedPlane() {
        try (SectionMeshAccumulatorTest.TestSprite base =
                        new SectionMeshAccumulatorTest.TestSprite("captured_grass_side");
                SectionMeshAccumulatorTest.TestSprite overlay =
                        new SectionMeshAccumulatorTest.TestSprite(
                                "captured_grass_side_overlay")) {
            base.fill(0xff70_5030);
            overlay.fill(0);
            overlay.setPixel(0, 0, 0xff80_c060);
            int tint = 0xff70_d050;
            CapturedSectionGeometry section = capturedLayeredFace(
                    base,
                    overlay,
                    3.0F,
                    new int[] {tint, tint, tint, tint});

            CpuClusterMesh cluster = translate(section);

            assertEquals(1, cluster.voxelMeshes().size());
            assertEquals(1, cluster.voxelInstances().count());
            assertEquals(3.0F, cluster.voxelInstances().translationZ(0), 0.0F);
            assertEquals(
                    PrimitivePacking.packTint(tint) & 0x00ff_ffff,
                    cluster.voxelInstances().packedTint(0));
            assertEquals(0L, cluster.opaqueTriangleCount());
            assertEquals(0L, cluster.cutoutTriangleCount());

            CompiledCluster compiled =
                    new CompiledCluster(0L, 0, 0, 0, cluster);
            CompiledCluster decoded =
                    CompiledClusterCodec.decode(
                            CompiledClusterCodec.encode(compiled));
            assertEquals(
                    CompiledClusterFingerprint.sha256Hex(compiled),
                    CompiledClusterFingerprint.sha256Hex(decoded));
        }
    }

    @Test
    void fabricVertexColorsAreAveragedOnlyInsideClusterTranslation() {
        try (SectionMeshAccumulatorTest.TestSprite base =
                        new SectionMeshAccumulatorTest.TestSprite("fabric_grass_side");
                SectionMeshAccumulatorTest.TestSprite overlay =
                        new SectionMeshAccumulatorTest.TestSprite(
                                "fabric_grass_side_overlay")) {
            base.fill(0xff60_4020);
            overlay.fill(0xff40_a040);
            int[] colors = {
                0xff20_8040,
                0xff40_a060,
                0xff60_c080,
                0xff80_e0a0
            };
            CapturedSectionGeometry section =
                    capturedLayeredFace(base, overlay, 5.0F, colors);

            CpuClusterMesh first = translate(section);
            CpuClusterMesh second = translate(section);
            int expected = ClusterSceneTranslator.averageColor(
                    section.quads().get(1).surface());

            assertEquals(1, first.voxelInstances().count());
            assertEquals(5.0F, first.voxelInstances().translationZ(0), 0.0F);
            assertEquals(
                    PrimitivePacking.packTint(expected) & 0x00ff_ffff,
                    first.voxelInstances().packedTint(0));
            assertArrayEquals(
                    first.voxelInstances().packedTints(),
                    second.voxelInstances().packedTints());
            assertArrayEquals(
                    first.voxelInstances().translations(),
                    second.voxelInstances().translations());
            assertArrayEquals(
                    first.voxelMeshes().getFirst().positions(),
                    second.voxelMeshes().getFirst().positions());
            assertArrayEquals(
                    first.voxelMeshes().getFirst().primitiveRecords(),
                    second.voxelMeshes().getFirst().primitiveRecords());
        }
    }

    @Test
    void coincidentCutoutWithoutRasterOverlayRoleIsNotBakedIntoOpaqueBase() {
        try (SectionMeshAccumulatorTest.TestSprite base =
                        new SectionMeshAccumulatorTest.TestSprite("ordinary_base");
                SectionMeshAccumulatorTest.TestSprite cutout =
                        new SectionMeshAccumulatorTest.TestSprite("ordinary_cutout")) {
            base.fill(0xff70_5030);
            cutout.fill(0xff80_c060);
            CapturedSectionGeometry.MutableQuad quad = face(1.0F);
            CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
            section.add(quad, surface(base, -1, false, false));
            section.add(quad, surface(cutout, -1, true, false));
            CapturedSectionGeometry captured = section.build();

            CpuClusterMesh detailed = translate(captured);
            CpuClusterMesh ordinary = translate(captured, false, false);

            assertEquals(2, detailed.voxelInstances().count());
            assertEquals(0, ordinary.voxelInstances().count());
            assertEquals(2L, ordinary.opaqueTriangleCount());
            assertEquals(2L, ordinary.cutoutTriangleCount());
        }
    }

    @Test
    void rasterFrontBackPairsBecomeOneTwoSidedCrossSheet() {
        try (SectionMeshAccumulatorTest.TestSprite grass =
                new SectionMeshAccumulatorTest.TestSprite("modded_cross_grass")) {
            grass.fill(0xff40_a040);
            CapturedSectionGeometry.MutableQuad firstPlane =
                    diagonalFace(false);
            CapturedSectionGeometry.MutableQuad firstBack =
                    rasterBack(firstPlane);
            CapturedSectionGeometry.MutableQuad secondPlane =
                    diagonalFace(true);
            CapturedSectionGeometry.MutableQuad secondBack =
                    rasterBack(secondPlane);
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            CapturedSectionGeometry.Surface surface = crossSurface(grass);
            section.add(firstPlane, surface);
            section.add(secondPlane, surface);
            section.add(firstBack, surface);
            section.add(secondBack, surface);

            CpuClusterMesh cluster = translate(section.build());

            assertEquals(4L, cluster.cutoutTriangleCount());
            assertEquals(0L, cluster.opaqueTriangleCount());
            assertEquals(0L, cluster.transmissiveTriangleCount());
            int[] primitives = cluster.segments().getFirst().primitiveRecords();
            assertEquals(
                    PrimitivePacking.packUv(
                            firstPlane.u[0], firstPlane.v[0]),
                    primitives[0]);
            assertEquals(
                    PrimitivePacking.packUv(
                            firstPlane.u[1], firstPlane.v[1]),
                    primitives[1]);
            assertEquals(
                    PrimitivePacking.packUv(
                            firstPlane.u[2], firstPlane.v[2]),
                    primitives[2]);
            assertEquals(
                    PrimitivePacking.packOctahedralNormal(
                            -0.70710677F, 0.0F, 0.70710677F),
                    primitives[4]);
            assertTwoSided(cluster);
        }
    }

    @Test
    void grassSideOverlayBecomesOneSurfaceRelationWhenReliefIsDisabled() {
        try (SectionMeshAccumulatorTest.TestSprite base =
                        new SectionMeshAccumulatorTest.TestSprite(
                                "flat_grass_side", 32, 16, 0, 0);
                SectionMeshAccumulatorTest.TestSprite overlay =
                        new SectionMeshAccumulatorTest.TestSprite(
                                "flat_grass_side_overlay", 32, 16, 16, 0)) {
            base.fill(0xff70_5030);
            overlay.fill(0);
            overlay.setPixel(0, 0, 0xff80_c060);
            CapturedSectionGeometry section = capturedLayeredFace(
                    base,
                    overlay,
                    4.0F,
                    new int[] {
                        0xff70_d050,
                        0xff70_d050,
                        0xff70_d050,
                        0xff70_d050
                    });

            CpuClusterMesh cluster = translate(section, false, false);

            assertEquals(2L, cluster.opaqueTriangleCount());
            assertEquals(0L, cluster.cutoutTriangleCount());
            assertEquals(0, cluster.voxelMeshes().size());
            assertEquals(0, cluster.voxelInstances().count());
            CpuClusterMesh.Segment segment = cluster.segments().getFirst();
            assertEquals(2, segment.opaqueTriangleCount());
            int[] primitives = segment.primitiveRecords();
            for (int record = 0;
                    record < primitives.length;
                    record += CpuSectionMesh.PRIMITIVE_WORDS) {
                int flags = PrimitivePacking.unpackControl(
                        primitives[record + 3], primitives[record + 5]);
                assertEquals(
                        PrimitivePacking.NO_EMITTER_INDEX,
                        PrimitivePacking.unpackEmitterIndex(
                                primitives[record + 5]));
            }
            assertEquals(80L, cluster.surfaceRelationBytes());
            int primitiveCount = segment.opaquePrimitiveCount();
            for (int primitive = 0; primitive < primitiveCount; primitive++) {
                int[] relation = SurfaceRelationTable.record(
                        segment.surfaceRelationRecords(),
                        primitiveCount,
                        primitive);
                assertEquals(
                        CpuSectionMesh.SURFACE_RELATION_OVERLAY,
                        relation[0] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK);
                assertTrue((relation[0] >> 8
                        & PrimitivePacking.CONTROL_ALPHA_CUTOUT) != 0);
            }
            CompiledCluster compiled =
                    new CompiledCluster(0L, 0, 0, 0, cluster);
            CompiledCluster decoded = CompiledClusterCodec.decode(
                    CompiledClusterCodec.encode(compiled));
            assertEquals(
                    CompiledClusterFingerprint.sha256Hex(compiled),
                    CompiledClusterFingerprint.sha256Hex(decoded));
        }
    }

    @Test
    void vanillaFaceBakeryCrossPairMeetsReductionContract() {
        try (FaceBakerySprite bakedSprite = new FaceBakerySprite();
                SectionMeshAccumulatorTest.TestSprite grass =
                        new SectionMeshAccumulatorTest.TestSprite("vanilla_cross")) {
            BakedQuad.MaterialInfo material = new BakedQuad.MaterialInfo(
                    bakedSprite,
                    ChunkSectionLayer.CUTOUT,
                    null,
                    0,
                    false,
                    0);
            CuboidFace.UVs uvs = new CuboidFace.UVs(
                    0.0F, 0.0F, 16.0F, 16.0F);
            CuboidRotation rotation = new CuboidRotation(
                    new Vector3f(0.5F, 0.5F, 0.5F),
                    () -> new Matrix4f().rotationY((float) Math.toRadians(45.0)),
                    true);
            ModelBaker.Interner interner = new ModelBaker.Interner() {
                @Override
                public Vector3fc vector(Vector3fc vector) {
                    return vector;
                }

                @Override
                public BakedQuad.MaterialInfo materialInfo(
                        BakedQuad.MaterialInfo materialInfo) {
                    return materialInfo;
                }
            };
            ModelState modelState = new ModelState() {
            };
            Vector3f from = new Vector3f(0.8F, 0.0F, 8.0F);
            Vector3f to = new Vector3f(15.2F, 16.0F, 8.0F);
            BakedQuad north = FaceBakery.bakeQuad(
                    interner,
                    from,
                    to,
                    uvs,
                    Quadrant.R0,
                    material,
                    Direction.NORTH,
                    modelState,
                    rotation);
            BakedQuad south = FaceBakery.bakeQuad(
                    interner,
                    from,
                    to,
                    uvs,
                    Quadrant.R0,
                    material,
                    Direction.SOUTH,
                    modelState,
                    rotation);
            Vector3f crossingFrom = new Vector3f(8.0F, 0.0F, 0.8F);
            Vector3f crossingTo = new Vector3f(8.0F, 16.0F, 15.2F);
            BakedQuad west = FaceBakery.bakeQuad(
                    interner,
                    crossingFrom,
                    crossingTo,
                    uvs,
                    Quadrant.R0,
                    material,
                    Direction.WEST,
                    modelState,
                    rotation);
            BakedQuad east = FaceBakery.bakeQuad(
                    interner,
                    crossingFrom,
                    crossingTo,
                    uvs,
                    Quadrant.R0,
                    material,
                    Direction.EAST,
                    modelState,
                    rotation);
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            CapturedSectionGeometry.Surface surface = crossSurface(grass);
            section.add(captured(north), surface);
            section.add(captured(south), surface);
            section.add(captured(west), surface);
            section.add(captured(east), surface);
            CapturedSectionGeometry captured = section.build();

            java.util.List<CapturedSectionGeometry.Quad> reduced =
                    TwoSidedQuadReducer.reduce(captured.quads());

            assertEquals(2, reduced.size());
            assertSame(captured.quads().getFirst(), reduced.getFirst());
            assertSame(captured.quads().get(2), reduced.get(1));
        }
    }

    @Test
    void oppositeCutoutQuadsWithDifferentTextureDomainBecomeBilateral() {
        try (SectionMeshAccumulatorTest.TestSprite grass =
                new SectionMeshAccumulatorTest.TestSprite("directional_cross")) {
            grass.fill(0xff40_a040);
            CapturedSectionGeometry.MutableQuad front =
                    diagonalFace(false);
            CapturedSectionGeometry.MutableQuad back = rasterBack(front);
            back.u[0] = 0.25F;
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            CapturedSectionGeometry.Surface surface = crossSurface(grass);
            section.add(front, surface);
            section.add(back, surface);

            CpuClusterMesh cluster = translate(section.build());

            assertEquals(2L, cluster.cutoutTriangleCount());
            assertBilateral(cluster);
            assertEquals(0, cluster.opacityMicromap().blockCount());
            for (int index : cluster.opacityMicromap().triangleIndices()) {
                assertTrue(index < 0);
            }
        }
    }

    @Test
    void vanillaSunflowerDiscKeepsDistinctDirectionalMaterialsWithoutHitCompetition() {
        try (FaceBakerySprite bakedSprite = new FaceBakerySprite();
                SectionMeshAccumulatorTest.TestSprite front =
                        new SectionMeshAccumulatorTest.TestSprite(
                                "sunflower_front");
                SectionMeshAccumulatorTest.TestSprite back =
                        new SectionMeshAccumulatorTest.TestSprite(
                                "sunflower_back")) {
            front.fill(0xffff_c040);
            back.fill(0xff60_5020);
            BakedQuad.MaterialInfo frontMaterial = new BakedQuad.MaterialInfo(
                    bakedSprite,
                    ChunkSectionLayer.CUTOUT,
                    null,
                    0,
                    false,
                    0);
            BakedQuad.MaterialInfo backMaterial = new BakedQuad.MaterialInfo(
                    bakedSprite,
                    ChunkSectionLayer.CUTOUT,
                    null,
                    0,
                    false,
                    0);
            CuboidFace.UVs uvs = new CuboidFace.UVs(
                    0.0F, 0.0F, 16.0F, 16.0F);
            CuboidRotation rotation = new CuboidRotation(
                    new Vector3f(0.5F, 0.5F, 0.5F),
                    () -> new Matrix4f().rotationZ(
                            (float) Math.toRadians(22.5)),
                    true);
            ModelBaker.Interner interner = passthroughInterner();
            ModelState modelState = new ModelState() {
            };
            Vector3f from = new Vector3f(9.6F, -1.0F, 1.0F);
            Vector3f to = new Vector3f(9.6F, 15.0F, 15.0F);
            BakedQuad west = FaceBakery.bakeQuad(
                    interner,
                    from,
                    to,
                    uvs,
                    Quadrant.R0,
                    backMaterial,
                    Direction.WEST,
                    modelState,
                    rotation);
            BakedQuad east = FaceBakery.bakeQuad(
                    interner,
                    from,
                    to,
                    uvs,
                    Quadrant.R0,
                    frontMaterial,
                    Direction.EAST,
                    modelState,
                    rotation);
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            section.add(captured(west), crossSurface(back));
            section.add(captured(east), crossSurface(front));
            CapturedSectionGeometry captured = section.build();

            java.util.List<TwoSidedQuadReducer.ResolvedQuad> resolved =
                    TwoSidedQuadReducer.resolve(captured.quads());
            CpuClusterMesh cluster = translate(captured, false, false);

            assertEquals(1, resolved.size());
            assertEquals(
                    SurfaceDefinition.InterfaceMode.BILATERAL,
                    resolved.getFirst().definition().interfaceMode());
            assertEquals(2L, cluster.cutoutTriangleCount());
            assertBilateral(cluster);
            assertEquals(0, cluster.opacityMicromap().blockCount());
        }
    }

    private static void assertBilateral(CpuClusterMesh cluster) {
        CpuClusterMesh.Segment segment = cluster.segments().getFirst();
        int primitiveCount = segment.opaquePrimitiveCount()
                + segment.cutoutPrimitiveCount()
                + segment.transmissivePrimitiveCount();
        int first = segment.opaquePrimitiveCount();
        for (int primitive = 0; primitive < segment.cutoutPrimitiveCount(); primitive++) {
            int[] relation = SurfaceRelationTable.record(
                    segment.surfaceRelationRecords(),
                    primitiveCount,
                    first + primitive);
            assertEquals(
                    CpuSectionMesh.SURFACE_RELATION_BILATERAL,
                    relation[0] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK);
        }
    }

    static CapturedSectionGeometry capturedLayeredFace(
            SectionMeshAccumulatorTest.TestSprite base,
            SectionMeshAccumulatorTest.TestSprite overlay,
            float plane,
            int[] overlayColors) {
        CapturedSectionGeometry.MutableQuad baseQuad = face(plane);
        setSpriteUv(baseQuad, base);
        CapturedSectionGeometry.MutableQuad overlayQuad = face(plane);
        setSpriteUv(overlayQuad, overlay);
        CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
        section.add(baseQuad, surface(base, -1, false, false));
        section.add(overlayQuad, new CapturedSectionGeometry.Surface(
                overlayColors[0],
                overlayColors[1],
                overlayColors[2],
                overlayColors[3],
                CapturedSectionGeometry.Layer.CUTOUT,
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                0,
                overlay.sprite(),
                null));
        for (int vertex = 0; vertex < 4; vertex++) {
            baseQuad.z[vertex] = plane + 7.0F;
            overlayQuad.z[vertex] = plane + 7.0F;
        }
        return section.build();
    }

    private static void setSpriteUv(
            CapturedSectionGeometry.MutableQuad quad,
            SectionMeshAccumulatorTest.TestSprite sprite) {
        quad.u[0] = 0.0F;
        quad.v[0] = 0.0F;
        quad.u[1] = 1.0F;
        quad.v[1] = 0.0F;
        quad.u[2] = 1.0F;
        quad.v[2] = 1.0F;
        quad.u[3] = 0.0F;
        quad.v[3] = 1.0F;
    }

    private static CapturedSectionGeometry.Surface surface(
            SectionMeshAccumulatorTest.TestSprite sprite,
            int color,
            boolean cutout,
            boolean rasterOverlay) {
        return CapturedSectionGeometry.Surface.uniform(
                color,
                cutout
                        ? CapturedSectionGeometry.Layer.CUTOUT
                        : CapturedSectionGeometry.Layer.OPAQUE,
                false,
                false,
                false,
                false,
                false,
                true,
                rasterOverlay,
                0,
                sprite.sprite());
    }

    private static CapturedSectionGeometry.MutableQuad face(float plane) {
        CapturedSectionGeometry.MutableQuad quad =
                new CapturedSectionGeometry.MutableQuad();
        quad.x[0] = 0.0F;
        quad.y[0] = 0.0F;
        quad.x[1] = 1.0F;
        quad.y[1] = 0.0F;
        quad.x[2] = 1.0F;
        quad.y[2] = 1.0F;
        quad.x[3] = 0.0F;
        quad.y[3] = 1.0F;
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.z[vertex] = plane;
        }
        quad.u[0] = 0.0F;
        quad.v[0] = 0.0F;
        quad.u[1] = 1.0F;
        quad.v[1] = 0.0F;
        quad.u[2] = 1.0F;
        quad.v[2] = 1.0F;
        quad.u[3] = 0.0F;
        quad.v[3] = 1.0F;
        quad.normalZ = 1.0F;
        return quad;
    }

    private static CapturedSectionGeometry.MutableQuad diagonalFace(
            boolean oppositeDiagonal) {
        CapturedSectionGeometry.MutableQuad quad =
                new CapturedSectionGeometry.MutableQuad();
        float[] first = oppositeDiagonal
                ? new float[] {1.0F, 0.0F}
                : new float[] {0.0F, 0.0F};
        float[] second = oppositeDiagonal
                ? new float[] {0.0F, 1.0F}
                : new float[] {1.0F, 1.0F};
        quad.x[0] = first[0];
        quad.y[0] = 0.0F;
        quad.z[0] = first[1];
        quad.x[1] = second[0];
        quad.y[1] = 0.0F;
        quad.z[1] = second[1];
        quad.x[2] = second[0];
        quad.y[2] = 1.0F;
        quad.z[2] = second[1];
        quad.x[3] = first[0];
        quad.y[3] = 1.0F;
        quad.z[3] = first[1];
        quad.u[0] = 0.0F;
        quad.v[0] = 1.0F;
        quad.u[1] = 1.0F;
        quad.v[1] = 1.0F;
        quad.u[2] = 1.0F;
        quad.v[2] = 0.0F;
        quad.u[3] = 0.0F;
        quad.v[3] = 0.0F;
        quad.normalX = oppositeDiagonal ? 1.0F : -1.0F;
        quad.normalZ = 1.0F;
        return quad;
    }

    private static CapturedSectionGeometry.MutableQuad rasterBack(
            CapturedSectionGeometry.MutableQuad source) {
        CapturedSectionGeometry.MutableQuad back =
                new CapturedSectionGeometry.MutableQuad();
        int[] reverse = {0, 3, 2, 1};
        for (int vertex = 0; vertex < 4; vertex++) {
            int sourceVertex = reverse[vertex];
            back.x[vertex] = source.x[sourceVertex];
            back.y[vertex] = source.y[sourceVertex];
            back.z[vertex] = source.z[sourceVertex];
            back.u[vertex] = source.u[vertex];
            back.v[vertex] = source.v[vertex];
        }
        back.normalX = -source.normalX;
        back.normalY = -source.normalY;
        back.normalZ = -source.normalZ;
        return back;
    }

    private static CapturedSectionGeometry.MutableQuad captured(
            BakedQuad source) {
        CapturedSectionGeometry.MutableQuad captured =
                new CapturedSectionGeometry.MutableQuad();
        for (int vertex = 0; vertex < 4; vertex++) {
            Vector3fc position = source.position(vertex);
            captured.x[vertex] = position.x();
            captured.y[vertex] = position.y();
            captured.z[vertex] = position.z();
            long packedUv = source.packedUV(vertex);
            captured.u[vertex] = UVPair.unpackU(packedUv);
            captured.v[vertex] = UVPair.unpackV(packedUv);
        }
        captured.normalX = source.direction().getStepX();
        captured.normalY = source.direction().getStepY();
        captured.normalZ = source.direction().getStepZ();
        return captured;
    }

    private static ModelBaker.Interner passthroughInterner() {
        return new ModelBaker.Interner() {
            @Override
            public Vector3fc vector(Vector3fc vector) {
                return vector;
            }

            @Override
            public BakedQuad.MaterialInfo materialInfo(
                    BakedQuad.MaterialInfo materialInfo) {
                return materialInfo;
            }
        };
    }

    private static final class FaceBakerySprite extends TextureAtlasSprite {
        private FaceBakerySprite() {
            super(
                    Identifier.fromNamespaceAndPath("prime", "test_atlas"),
                    new SpriteContents(
                            Identifier.fromNamespaceAndPath("prime", "face_bakery"),
                            new FrameSize(16, 16),
                            new NativeImage(16, 16, true)),
                    16,
                    16,
                    0,
                    0,
                    0);
        }
    }

    private static void assertFrontFaceOnly(CpuClusterMesh cluster) {
        for (CpuClusterMesh.Segment segment : cluster.segments()) {
            int[] primitives = segment.primitiveRecords();
            for (int record = 0;
                    record < primitives.length;
                    record += CpuSectionMesh.PRIMITIVE_WORDS) {
                int flags = PrimitivePacking.unpackControl(
                        primitives[record + 3], primitives[record + 5]);
                assertTrue(
                        (flags & PrimitivePacking.CONTROL_FRONT_FACE_ONLY) != 0);
            }
        }
    }

    private static void assertTwoSided(CpuClusterMesh cluster) {
        for (CpuClusterMesh.Segment segment : cluster.segments()) {
            int[] primitives = segment.primitiveRecords();
            for (int record = 0;
                    record < primitives.length;
                    record += CpuSectionMesh.PRIMITIVE_WORDS) {
                int flags = PrimitivePacking.unpackControl(
                        primitives[record + 3], primitives[record + 5]);
                assertEquals(
                        0, flags & PrimitivePacking.CONTROL_FRONT_FACE_ONLY);
            }
        }
    }

    private static CapturedSectionGeometry.Surface crossSurface(
            SectionMeshAccumulatorTest.TestSprite sprite) {
        return CapturedSectionGeometry.Surface.uniform(
                0xff80_c060,
                CapturedSectionGeometry.Layer.CUTOUT,
                false,
                true,
                false,
                false,
                false,
                true,
                false,
                0,
                sprite.sprite());
    }

    private static CpuClusterMesh translate(CapturedSectionGeometry section) {
        return translate(section, false);
    }

    private static CpuClusterMesh translate(
            CapturedSectionGeometry section, boolean suppressFluidFace) {
        return translate(section, suppressFluidFace, true);
    }

    private static CpuClusterMesh translate(
            CapturedSectionGeometry section,
            boolean suppressFluidFace,
            boolean voxelSurfacesEnabled) {
        CapturedCluster.Builder captured = new CapturedCluster.Builder(0, 0, 0);
        captured.add(0, 0, 0, section);
        return translate(
                captured.build(), suppressFluidFace, voxelSurfacesEnabled);
    }

    static CpuClusterMesh translate(
            CapturedCluster captured, boolean suppressFluidFace) {
        return translate(captured, suppressFluidFace, true);
    }

    private static CpuClusterMesh translate(
            CapturedCluster captured,
            boolean suppressFluidFace,
            boolean voxelSurfacesEnabled) {
        return ClusterSceneTranslator.translate(
                captured,
                LabPbrMaterialSet.EMPTY,
                new ClusterTranslationSettings(
                        false,
                        TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                        OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                        voxelSurfacesEnabled,
                        VoxelSurfaceSettings.BASE_HEIGHT,
                        false,
                        suppressFluidFace));
    }
}
