package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.shader.ShaderAbi;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

final class FluidQuadTranslationTest {
    @Test
    void fullCollisionSuppressionIsIndependentOfTheLegacyPolicyFlag() {
        try (SectionMeshAccumulatorTest.TestSprite water =
                new SectionMeshAccumulatorTest.TestSprite("captured_water")) {
            water.fill(0xff40_80c0);
            CapturedSectionGeometry.MutableQuad outward = southFace();
            CapturedSectionGeometry.MutableQuad rasterBack = reversed(outward);
            int collisionMask = 1 << Direction.SOUTH.ordinal();
            CapturedSectionGeometry.Surface surface =
                    fluidSurface(water, collisionMask, true, 0);
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            section.add(outward, surface);
            section.add(rasterBack, surface);
            CapturedSectionGeometry captured = section.build();

            CpuClusterMesh translated = translate(captured, false);
            CpuClusterMesh suppressed = translate(captured, true);

            assertEquals(0L, translated.transmissiveTriangleCount());
            assertEquals(0L, suppressed.transmissiveTriangleCount());
        }
    }

    @Test
    void fullCollisionAlsoSuppressesASlopedInternalBoundary() {
        try (SectionMeshAccumulatorTest.TestSprite water =
                new SectionMeshAccumulatorTest.TestSprite("sloped_water")) {
            water.fill(0xff40_80c0);
            CapturedSectionGeometry.MutableQuad top = slopedTop();
            CapturedSectionGeometry.MutableQuad rasterBack = reversed(top);
            int collisionMask = 1 << Direction.UP.ordinal();
            CapturedSectionGeometry.Surface surface =
                    fluidSurface(water, collisionMask, true, 0);
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            section.add(top, surface);
            section.add(rasterBack, surface);
            CapturedSectionGeometry captured = section.build();

            CpuClusterMesh cluster = translate(captured, false);
            CpuClusterMesh suppressed = translate(captured, true);

            assertEquals(0L, cluster.transmissiveTriangleCount());
            assertEquals(0L, cluster.opaqueTriangleCount());
            assertEquals(0, cluster.lights().emitterCount());
            assertEquals(0L, suppressed.transmissiveTriangleCount());
        }
    }

    @Test
    void warpedWaterRasterPairBecomesOneOutwardBoundary() {
        try (SectionMeshAccumulatorTest.TestSprite water =
                new SectionMeshAccumulatorTest.TestSprite("warped_water")) {
            water.fill(0xff40_80c0);
            CapturedSectionGeometry.MutableQuad top = warpedTop();
            CapturedSectionGeometry.MutableQuad rasterBack = reversed(top);
            setGeometricNormal(rasterBack);
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            CapturedSectionGeometry.Surface surface =
                    fluidSurface(water, 0, true, 0);
            section.add(top, surface);
            section.add(rasterBack, surface);

            CpuClusterMesh cluster = translate(section.build(), false);

            assertEquals(2L, cluster.transmissiveTriangleCount());
            assertAllTriangleNormalsHaveYSign(cluster, 1.0F);
        }
    }

    @Test
    void shallowSlopedWaterWithoutRasterBackFaceIsNotDiscarded() {
        try (SectionMeshAccumulatorTest.TestSprite water =
                new SectionMeshAccumulatorTest.TestSprite("one_sided_sloped_water")) {
            water.fill(0xff40_80c0);
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            section.add(slopedTop(), fluidSurface(water, 0, true, 0));

            CpuClusterMesh cluster = translate(section.build(), false);

            assertEquals(2L, cluster.transmissiveTriangleCount());
            assertAllTriangleNormalsHaveYSign(cluster, 1.0F);
        }
    }

    @Test
    void slopedLavaEmitsFromItsAuthoredOuterSide() {
        try (SectionMeshAccumulatorTest.TestSprite lava =
                new SectionMeshAccumulatorTest.TestSprite("sloped_lava")) {
            lava.fill(0xffff_6000);
            CapturedSectionGeometry.MutableQuad top = slopedTop();
            CapturedSectionGeometry.MutableQuad rasterBack = reversed(top);
            CapturedSectionGeometry.Surface surface =
                    fluidSurface(lava, 0, false, 15);
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            section.add(top, surface);
            section.add(rasterBack, surface);

            CpuClusterMesh cluster = translate(section.build(), false);

            assertEquals(2L, cluster.opaqueTriangleCount());
            assertEquals(0L, cluster.transmissiveTriangleCount());
            assertEquals(2, cluster.lights().emitterCount());
            assertAllTriangleNormalsHaveYSign(cluster, 1.0F);
            assertAllPrimitivesReferenceEmitters(cluster);
            assertAllEmitterNormalsHaveYSign(cluster.lights(), 1.0F);
        }
    }

    @Test
    void bottomKeepsItsAuthoredDownwardBoundary() {
        try (SectionMeshAccumulatorTest.TestSprite water =
                new SectionMeshAccumulatorTest.TestSprite("water_bottom")) {
            water.fill(0xff40_80c0);
            CapturedSectionGeometry.Builder section =
                    new CapturedSectionGeometry.Builder();
            section.add(bottom(), fluidSurface(water, 0, true, 0));

            CpuClusterMesh cluster = translate(section.build(), false);

            assertEquals(2L, cluster.transmissiveTriangleCount());
            assertAllTriangleNormalsHaveYSign(cluster, -1.0F);
        }
    }

    private static CapturedSectionGeometry.MutableQuad southFace() {
        CapturedSectionGeometry.MutableQuad quad =
                new CapturedSectionGeometry.MutableQuad();
        quad.x[0] = 0.0F;
        quad.y[0] = 0.0F;
        quad.z[0] = 1.0F;
        quad.x[1] = 1.0F;
        quad.y[1] = 0.0F;
        quad.z[1] = 1.0F;
        quad.x[2] = 1.0F;
        quad.y[2] = 1.0F;
        quad.z[2] = 1.0F;
        quad.x[3] = 0.0F;
        quad.y[3] = 1.0F;
        quad.z[3] = 1.0F;
        setUnitUv(quad);
        setGeometricNormal(quad);
        return quad;
    }

    private static CapturedSectionGeometry.MutableQuad slopedTop() {
        CapturedSectionGeometry.MutableQuad quad =
                new CapturedSectionGeometry.MutableQuad();
        quad.x[0] = 0.0F;
        quad.y[0] = 0.20F;
        quad.z[0] = 0.0F;
        quad.x[1] = 0.0F;
        quad.y[1] = 0.25F;
        quad.z[1] = 1.0F;
        quad.x[2] = 1.0F;
        quad.y[2] = 0.35F;
        quad.z[2] = 1.0F;
        quad.x[3] = 1.0F;
        quad.y[3] = 0.30F;
        quad.z[3] = 0.0F;
        setUnitUv(quad);
        setGeometricNormal(quad);
        return quad;
    }

    private static CapturedSectionGeometry.MutableQuad warpedTop() {
        CapturedSectionGeometry.MutableQuad quad = slopedTop();
        quad.y[0] = 0.999F;
        quad.y[1] = 0.999F;
        quad.y[2] = 0.999F;
        quad.y[3] = 0.388F;
        setGeometricNormal(quad);
        return quad;
    }

    private static CapturedSectionGeometry.MutableQuad bottom() {
        CapturedSectionGeometry.MutableQuad quad =
                new CapturedSectionGeometry.MutableQuad();
        quad.x[0] = 0.0F;
        quad.y[0] = 0.001F;
        quad.z[0] = 0.0F;
        quad.x[1] = 1.0F;
        quad.y[1] = 0.001F;
        quad.z[1] = 0.0F;
        quad.x[2] = 1.0F;
        quad.y[2] = 0.001F;
        quad.z[2] = 1.0F;
        quad.x[3] = 0.0F;
        quad.y[3] = 0.001F;
        quad.z[3] = 1.0F;
        setUnitUv(quad);
        setGeometricNormal(quad);
        return quad;
    }

    private static CapturedSectionGeometry.MutableQuad reversed(
            CapturedSectionGeometry.MutableQuad source) {
        CapturedSectionGeometry.MutableQuad reversed =
                new CapturedSectionGeometry.MutableQuad();
        int[] order = {0, 3, 2, 1};
        for (int vertex = 0; vertex < 4; vertex++) {
            int sourceVertex = order[vertex];
            reversed.x[vertex] = source.x[sourceVertex];
            reversed.y[vertex] = source.y[sourceVertex];
            reversed.z[vertex] = source.z[sourceVertex];
            reversed.u[vertex] = source.u[sourceVertex];
            reversed.v[vertex] = source.v[sourceVertex];
        }
        reversed.normalX = -source.normalX;
        reversed.normalY = -source.normalY;
        reversed.normalZ = -source.normalZ;
        return reversed;
    }

    private static void setUnitUv(CapturedSectionGeometry.MutableQuad quad) {
        quad.u[0] = 0.0F;
        quad.v[0] = 0.0F;
        quad.u[1] = 0.0F;
        quad.v[1] = 1.0F;
        quad.u[2] = 1.0F;
        quad.v[2] = 1.0F;
        quad.u[3] = 1.0F;
        quad.v[3] = 0.0F;
    }

    private static void setGeometricNormal(
            CapturedSectionGeometry.MutableQuad quad) {
        float edgeOneX = quad.x[1] - quad.x[0];
        float edgeOneY = quad.y[1] - quad.y[0];
        float edgeOneZ = quad.z[1] - quad.z[0];
        float edgeTwoX = quad.x[2] - quad.x[0];
        float edgeTwoY = quad.y[2] - quad.y[0];
        float edgeTwoZ = quad.z[2] - quad.z[0];
        float normalX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
        float normalY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
        float normalZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
        float inverseLength = 1.0F / (float) Math.sqrt(
                normalX * normalX + normalY * normalY + normalZ * normalZ);
        quad.normalX = normalX * inverseLength;
        quad.normalY = normalY * inverseLength;
        quad.normalZ = normalZ * inverseLength;
    }

    private static CapturedSectionGeometry.Surface fluidSurface(
            SectionMeshAccumulatorTest.TestSprite sprite,
            int collisionMask,
            boolean water,
            int lightEmission) {
        return new CapturedSectionGeometry.Surface(
                -1,
                -1,
                -1,
                -1,
                water
                        ? CapturedSectionGeometry.Layer.TRANSLUCENT
                        : CapturedSectionGeometry.Layer.OPAQUE,
                false,
                false,
                false,
                water,
                false,
                false,
                false,
                lightEmission,
                sprite.sprite(),
                new CapturedSectionGeometry.FluidFacts(
                        0, 0, 0, false, collisionMask));
    }

    private static CpuClusterMesh translate(
            CapturedSectionGeometry section, boolean suppressFluidFace) {
        CapturedCluster.Builder captured = new CapturedCluster.Builder(0, 0, 0);
        captured.add(0, 0, 0, section);
        return ClusterSceneTranslator.translate(
                captured.build(),
                LabPbrMaterialSet.EMPTY,
                new ClusterTranslationSettings(
                        false,
                        TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                        OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                        true,
                        VoxelSurfaceSettings.BASE_HEIGHT,
                        false,
                        suppressFluidFace));
    }

    private static void assertAllTriangleNormalsHaveYSign(
            CpuClusterMesh cluster, float expectedSign) {
        for (CpuClusterMesh.Segment segment : cluster.segments()) {
            float[] positions = segment.positions();
            for (int triangle = 0; triangle < segment.triangleCount(); triangle++) {
                int offset = triangle * 9;
                float edgeOneX = positions[offset + 3] - positions[offset];
                float edgeOneZ = positions[offset + 5] - positions[offset + 2];
                float edgeTwoX = positions[offset + 6] - positions[offset];
                float edgeTwoZ = positions[offset + 8] - positions[offset + 2];
                float normalY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
                assertTrue(expectedSign * normalY > 0.0F);
            }
        }
    }

    private static void assertAllEmitterNormalsHaveYSign(
            CompiledClusterLights lights, float expectedSign) {
        int[] words = lights.encodedWords();
        int emitterWord = words[6] / Integer.BYTES;
        int emitterWords = ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES;
        for (int emitter = 0; emitter < lights.emitterCount(); emitter++) {
            float normalY = Float.intBitsToFloat(
                    words[emitterWord + emitter * emitterWords + 13]);
            assertTrue(expectedSign * normalY > 0.5F);
        }
    }

    private static void assertAllPrimitivesReferenceEmitters(
            CpuClusterMesh cluster) {
        for (CpuClusterMesh.Segment segment : cluster.segments()) {
            int[] primitives = segment.primitiveRecords();
            for (int triangle = 0; triangle < segment.triangleCount(); triangle++) {
                int packedFlagsEmitter =
                        primitives[triangle * CpuSectionMesh.PRIMITIVE_WORDS + 5];
                assertTrue(
                        PrimitivePacking.unpackEmitterIndex(packedFlagsEmitter)
                                >= 0);
            }
        }
    }

}
