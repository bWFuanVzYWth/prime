package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

final class TextureVoxelSurfaceTest {
    @Test
    void bt601LumaMapsBlackAndWhiteToTheDeclaredOutwardRange() {
        assertEquals(0.0F, TextureVoxelMeshBuilder.heightFromArgb(0xff00_0000));
        assertEquals(
                1.0F / 16.0F,
                TextureVoxelMeshBuilder.heightFromArgb(0xffff_ffff));
        assertEquals(
                77.0F / 256.0F / 16.0F,
                TextureVoxelMeshBuilder.heightFromArgb(0xffff_0000),
                1.0E-7F);
        assertEquals(
                150.0F / 256.0F / 16.0F,
                TextureVoxelMeshBuilder.heightFromArgb(0xff00_ff00),
                1.0E-7F);
        assertEquals(
                29.0F / 256.0F / 16.0F,
                TextureVoxelMeshBuilder.heightFromArgb(0xff00_00ff),
                1.0E-7F);
    }

    @Test
    void detailWindowUsesExactlyThreeClustersPerAxis() {
        int count = 0;
        for (int z = -8; z <= 8; z += SectionCluster.SECTION_SIZE) {
            for (int y = -8; y <= 8; y += SectionCluster.SECTION_SIZE) {
                for (int x = -8; x <= 8; x += SectionCluster.SECTION_SIZE) {
                    if (VoxelSurfaceCoverage.includes(
                            x, y, z, 0, 0, 0)) {
                        count++;
                    }
                }
            }
        }
        assertEquals(27, count);
        assertTrue(VoxelSurfaceCoverage.includes(
                -4, -4, -4, 0, 0, 0));
        assertTrue(VoxelSurfaceCoverage.includes(
                4, 4, 4, 3, 3, 3));
        assertFalse(VoxelSurfaceCoverage.includes(
                8, 0, 0, 0, 0, 0));
        assertFalse(VoxelSurfaceCoverage.includes(
                -8, 0, 0, 0, 0, 0));
        assertFalse(VoxelSurfaceCoverage.includes(
                0, 8, 0, 0, 0, 0));
        assertTrue(VoxelSurfaceCoverage.includes(
                -8, 0, 0, -1, -1, -1));
        assertFalse(VoxelSurfaceCoverage.includes(
                4, 0, 0, -1, -1, -1));
    }

    @Test
    void crossingOneClusterInvalidatesOnlyTheEnteringAndLeavingSlabs() {
        assertEquals(
                18,
                VoxelSurfaceCoverage.changedKeys(0, 0, 0, 4, 0, 0).length);
        assertEquals(
                0,
                VoxelSurfaceCoverage.changedKeys(0, 0, 0, 0, 0, 0).length);
    }

    @Test
    void labPbrNormalAlphaProvidesRebasedAnimatedHeight() {
        LabPbrHeightMap height = LabPbrHeightMap.fromNormal(
                new int[] {
                    0xd600_0000, 0xfd00_0000,
                    0x8000_0000, 0xff00_0000
                },
                2,
                2,
                2,
                1,
                1,
                2);

        assertEquals(0.0F, height.sample(0, 0.25F, 0.5F));
        assertEquals(39.0F / 255.0F, height.sample(0, 0.75F, 0.5F));
        assertEquals(0.0F, height.sample(1, 0.25F, 0.5F));
        assertEquals(127.0F / 255.0F, height.sample(1, 0.75F, 0.5F));

        LabPbrHeightMap flat = LabPbrHeightMap.fromNormal(
                new int[] {0xff00_0000, 0xff00_0000},
                2,
                1,
                2,
                1,
                1,
                1);
        assertEquals(0.0F, flat.sample(0, 0.25F, 0.5F));
        assertEquals(0.0F, flat.sample(0, 0.75F, 0.5F));
    }

    @Test
    void borderReferenceUsesTheDarkestOuterPixel() {
        float[] heights = {
            0.4F, 0.5F, 0.6F,
            0.7F, 0.1F, 0.8F,
            0.9F, 0.3F, 1.0F
        };

        assertEquals(
                0.3F,
                TextureVoxelMeshBuilder.borderReferenceHeight(heights, 3));
        assertEquals(
                0.1F,
                TextureVoxelMeshBuilder.borderReferenceHeight(
                        new float[] {0.1F}, 1));

        TextureVoxelMeshBuilder.alignToReferencePlane(heights, 0.3F);
        assertArrayEquals(
                new float[] {
                    0.1F, 0.2F, 0.3F,
                    0.4F, 0.0F, 0.5F,
                    0.6F, 0.0F, 0.7F
                },
                heights,
                1.0E-7F);
    }

    @Test
    void voxelSurfaceStrengthDefaultsToOneSixteenth() {
        assertEquals(0.0F, VoxelSurfaceSettings.maximumHeight(0));
        assertEquals(
                1.0F / 16.0F,
                VoxelSurfaceSettings.maximumHeight(
                        VoxelSurfaceSettings.DEFAULT_STEPS));
        assertEquals(
                1.0F / 8.0F,
                VoxelSurfaceSettings.maximumHeight(
                        VoxelSurfaceSettings.MAXIMUM_STEPS));
    }

    @Test
    void generatedReliefUsesAbsoluteLumaStrength() {
        CpuVoxelMesh generated = TextureVoxelMeshBuilder.buildOpaqueHeightField(
                2,
                new int[] {
                    0xff80_8080, 0xff00_0000,
                    0xff00_0000, 0xff00_0000
                },
                2,
                1);

        assertEquals(
                TextureVoxelMeshBuilder.heightFromArgb(0xff80_8080),
                maximumAxis(generated.positions(), 2),
                1.0E-7F);
    }

    @Test
    void staticCutoutLayerBakesIntoOneOpaqueBaseMesh() {
        try (SectionMeshAccumulatorTest.TestSprite baseSprite =
                        new SectionMeshAccumulatorTest.TestSprite("layer_base");
                SectionMeshAccumulatorTest.TestSprite overlaySprite =
                        new SectionMeshAccumulatorTest.TestSprite("layer_overlay")) {
            int baseColor = 0xff60_4020;
            int overlayColor = 0xff40_c060;
            int overlayTint = 0xff80_ff80;
            baseSprite.fill(baseColor);
            overlaySprite.fill(0);
            for (int x = 0; x < 16; x++) {
                overlaySprite.setPixel(x, 0, overlayColor);
            }
            int[] baseNormal = new int[16 * 16];
            int[] overlayNormal = new int[16 * 16];
            int[] baseSpecular = new int[16 * 16];
            int[] overlaySpecular = new int[16 * 16];
            Arrays.fill(baseNormal, 0xff12_3456);
            Arrays.fill(overlayNormal, 0xff65_4321);
            Arrays.fill(baseSpecular, 0xffa0_b0c0);
            Arrays.fill(overlaySpecular, 0xff0a_0b0c);
            LabPbrHeightMap baseHeight = LabPbrHeightMap.fromNormal(
                    baseNormal, 16, 16, 16, 16, 1, 1);
            LabPbrMaterialMap baseMaterial = new LabPbrMaterialMap(
                    new LabPbrMaterialMap.Pixels(
                            baseNormal, 16, 16, 16, 1, 1),
                    new LabPbrMaterialMap.Pixels(
                            baseSpecular, 16, 16, 16, 1, 1));
            LabPbrMaterialMap overlayMaterial = new LabPbrMaterialMap(
                    new LabPbrMaterialMap.Pixels(
                            overlayNormal, 16, 16, 16, 1, 1),
                    new LabPbrMaterialMap.Pixels(
                            overlaySpecular, 16, 16, 16, 1, 1));
            LabPbrMaterialSet materials = new LabPbrMaterialSet(
                    Set.of(
                            baseSprite.id(),
                            overlaySprite.id()),
                    Set.of(
                            baseSprite.id(),
                            overlaySprite.id()),
                    Map.of(),
                    Map.of(baseSprite.id(), baseHeight),
                    Map.of(
                            baseSprite.id(), baseMaterial,
                            overlaySprite.id(), overlayMaterial));
            SectionMeshAccumulator.Quad face =
                    SectionMeshAccumulatorTest.horizontalQuad(
                            2.0F, 3.0F, 4.0F, 1.0F);
            CapturedSectionGeometry.MutableQuad capturedFace =
                    new CapturedSectionGeometry.MutableQuad();
            for (int vertex = 0; vertex < 4; vertex++) {
                capturedFace.x[vertex] = face.x[vertex];
                capturedFace.y[vertex] = face.y[vertex];
                capturedFace.z[vertex] = face.z[vertex];
                capturedFace.u[vertex] = face.u[vertex];
                capturedFace.v[vertex] = face.v[vertex];
            }
            capturedFace.normalX = face.normalX;
            capturedFace.normalY = face.normalY;
            capturedFace.normalZ = face.normalZ;
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            section.add(
                    capturedFace,
                    CapturedSectionGeometry.Surface.uniform(
                            -1,
                            CapturedSectionGeometry.Layer.OPAQUE,
                            false,
                            false,
                            false,
                            false,
                            false,
                            true,
                            false,
                            0,
                            baseSprite.sprite()));
            section.add(
                    capturedFace,
                    CapturedSectionGeometry.Surface.uniform(
                            overlayTint,
                            CapturedSectionGeometry.Layer.CUTOUT,
                            false,
                            false,
                            false,
                            false,
                            false,
                            true,
                            true,
                            0,
                            overlaySprite.sprite()));
            CapturedCluster.Builder captured =
                    new CapturedCluster.Builder(0, 0, 0);
            captured.add(0, 0, 0, section.build());

            CpuClusterMesh cluster = ClusterSceneTranslator.translate(
                    captured.build(),
                    materials,
                    new ClusterTranslationSettings(
                            false,
                            TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                            OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                            true,
                            VoxelSurfaceSettings.BASE_HEIGHT,
                            false,
                            false));

            assertEquals(1, cluster.voxelMeshes().size());
            assertEquals(1, cluster.voxelInstances().count());
            assertEquals(
                    4.0F,
                    cluster.voxelInstances().translationZ(0),
                    1.0E-7F);
            assertEquals(
                    PrimitivePacking.packTint(overlayTint) & 0x00ff_ffff,
                    cluster.voxelInstances().packedTint(0));

            CpuVoxelMesh mesh = cluster.voxelMeshes().getFirst();
            assertEquals(16 * 16 * 2, mesh.opaqueTriangleCount());
            assertEquals(0, mesh.cutoutTriangleCount());
            int[] primitives = mesh.primitiveRecords();
            assertEquals(0, primitives[4]);
            assertBakedMaterial(
                    primitives,
                    0,
                    overlayColor,
                    overlayNormal[0],
                    overlaySpecular[0],
                    false);
            int firstBaseTriangle = 16 * 2;
            assertBakedMaterial(
                    primitives,
                    firstBaseTriangle,
                    baseColor,
                    baseNormal[0],
                    baseSpecular[0],
                    true);
            assertEquals(
                    PrimitivePacking.CONTROL_NORMAL_TEXTURE
                            | PrimitivePacking.CONTROL_OPTICAL_TEXTURE,
                    PrimitivePacking.unpackControl(
                            primitives[3], primitives[5]));
        }
    }

    @Test
    void flatGeneratedTextureAlignsItsOuterSurfaceToTheBlockPlane() {
        CpuVoxelMesh outward = TextureVoxelMeshBuilder.buildOpaqueHeightField(
                2,
                new int[] {
                    0xffff_ffff, 0xffff_ffff,
                    0xffff_ffff, 0xffff_ffff
                },
                2,
                1);
        CpuVoxelMesh inwardFacing = TextureVoxelMeshBuilder.buildOpaqueHeightField(
                2,
                new int[] {
                    0xffff_ffff, 0xffff_ffff,
                    0xffff_ffff, 0xffff_ffff
                },
                2,
                -1);

        assertEquals(8, outward.triangleCount());
        assertEquals(0.0F, minimumAxis(outward.positions(), 2));
        assertEquals(0.0F, maximumAxis(outward.positions(), 2));
        assertEquals(0.0F, minimumAxis(inwardFacing.positions(), 2));
        assertEquals(0.0F, maximumAxis(inwardFacing.positions(), 2));
        assertNondegenerate(outward.positions());
        assertNondegenerate(inwardFacing.positions());
    }

    @Test
    void aSingleRaisedPixelAddsOnlyItsVisibleStepWalls() {
        CpuVoxelMesh flat = TextureVoxelMeshBuilder.buildOpaqueHeightField(
                2,
                new int[] {
                    0xff00_0000, 0xff00_0000,
                    0xff00_0000, 0xff00_0000
                },
                1,
                1);
        CpuVoxelMesh stepped = TextureVoxelMeshBuilder.buildOpaqueHeightField(
                2,
                new int[] {
                    0xffff_ffff, 0xff00_0000,
                    0xff00_0000, 0xff00_0000
                },
                1,
                1);

        assertEquals(8, flat.triangleCount());
        // Four top quads and four walls around the one raised corner column.
        assertEquals(16, stepped.triangleCount());
        assertEquals(0.0F, minimumAxis(stepped.positions(), 1));
        assertEquals(1.0F / 16.0F, maximumAxis(stepped.positions(), 1));
        assertNondegenerate(stepped.positions());
    }

    @Test
    void compiledClusterRoundTripPreservesReusableMeshesAndInstances() {
        CpuVoxelMesh voxelMesh = TextureVoxelMeshBuilder.buildOpaqueHeightField(
                1, new int[] {0xffff_ffff}, 2, 1);
        float[] positions = voxelMesh.positions();
        int[] primitives = voxelMesh.primitiveRecords();
        CpuVoxelInstances instances = new CpuVoxelInstances(
                new int[] {0, 0},
                new int[] {0x0012_3456, 0x00ab_cdef},
                new float[] {1.0F, 2.0F, 3.0F, 4.0F, 5.0F, 6.0F});
        CpuClusterMesh mesh = CpuClusterMesh.fromEncoded(
                List.of(),
                0L,
                0L,
                0L,
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.EMPTY,
                List.of(voxelMesh),
                instances);
        CompiledCluster source = new CompiledCluster(
                SectionPos.asLong(0, 0, 0), 0, 0, 0, mesh);

        CompiledCluster decoded =
                CompiledClusterCodec.decode(CompiledClusterCodec.encode(source));

        assertEquals(1, decoded.mesh().voxelMeshes().size());
        assertEquals(2, decoded.mesh().voxelInstances().count());
        assertArrayEquals(
                instances.meshIndices(),
                decoded.mesh().voxelInstances().meshIndices());
        assertArrayEquals(
                instances.packedTints(),
                decoded.mesh().voxelInstances().packedTints());
        assertArrayEquals(
                instances.translations(),
                decoded.mesh().voxelInstances().translations());
        assertArrayEquals(
                positions,
                decoded.mesh().voxelMeshes().getFirst().positions());
        assertArrayEquals(
                primitives,
                decoded.mesh().voxelMeshes().getFirst().primitiveRecords());
    }

    private static void assertBakedMaterial(
            int[] primitives,
            int triangle,
            int argb,
            int normalArgb,
            int specularArgb,
            boolean ownsTint) {
        int record = triangle * CpuSectionMesh.PRIMITIVE_WORDS;
        assertEquals(
                LabPbrMaterialMap.packArgb(normalArgb),
                primitives[record]);
        assertEquals(
                LabPbrMaterialMap.packArgb(specularArgb),
                primitives[record + 1]);
        assertEquals(
                PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL
                        | (ownsTint ? PrimitivePacking.CONSTANT_UV_OWN_TINT : 0),
                primitives[record + 2]);
        assertEquals(
                PrimitivePacking.packTint(argb) & 0x00ff_ffff,
                primitives[record + 3] & 0x00ff_ffff);
        assertEquals(
                PrimitivePacking.CONSTANT_UV_DENSITY,
                primitives[record + 6]);
    }

    private static float minimumAxis(float[] positions, int axis) {
        float result = Float.POSITIVE_INFINITY;
        for (int index = axis; index < positions.length; index += 3) {
            result = Math.min(result, positions[index]);
        }
        return result;
    }

    private static float maximumAxis(float[] positions, int axis) {
        float result = Float.NEGATIVE_INFINITY;
        for (int index = axis; index < positions.length; index += 3) {
            result = Math.max(result, positions[index]);
        }
        return result;
    }

    private static void assertNondegenerate(float[] positions) {
        for (int triangle = 0; triangle < positions.length / 9; triangle++) {
            int offset = triangle * 9;
            float edgeOneX = positions[offset + 3] - positions[offset];
            float edgeOneY = positions[offset + 4] - positions[offset + 1];
            float edgeOneZ = positions[offset + 5] - positions[offset + 2];
            float edgeTwoX = positions[offset + 6] - positions[offset];
            float edgeTwoY = positions[offset + 7] - positions[offset + 1];
            float edgeTwoZ = positions[offset + 8] - positions[offset + 2];
            float crossX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
            float crossY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
            float crossZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
            assertTrue(
                    crossX * crossX + crossY * crossY + crossZ * crossZ > 0.0F);
        }
    }
}
