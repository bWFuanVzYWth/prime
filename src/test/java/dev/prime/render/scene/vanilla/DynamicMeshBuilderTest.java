package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.PrimitiveTopology;
import dev.prime.render.terrain.CpuClusterMesh;
import dev.prime.render.terrain.PrimitivePacking;
import java.util.List;
import net.minecraft.util.LightCoordsUtil;
import org.junit.jupiter.api.Test;

final class DynamicMeshBuilderTest {
    @Test
    void capturesQuadForGiWithoutRegisteringVisibleEmissionAsALight() {
        DynamicMeshBuilder builder = new DynamicMeshBuilder(10.0, 20.0, 30.0);
        DynamicMeshBuilder.VertexSink sink = builder.open(
                VanillaSceneBoundary.Element.BLOCK_ENTITY,
                PrimitiveTopology.QUADS,
                7,
                LightCoordsUtil.FULL_BRIGHT);
        vertex(sink, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        vertex(sink, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F);
        vertex(sink, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F);
        vertex(sink, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F);
        sink.finish();

        DynamicSceneFrame frame = builder.build(0, 0, 0, List.of());
        CpuClusterMesh mesh = frame.mesh();

        assertEquals(2L, mesh.triangleCount());
        assertEquals(2L, mesh.cutoutTriangleCount());
        assertEquals(2, frame.blockEntityTriangles());
        assertEquals(0, frame.entityTriangles());
        assertEquals(0, frame.particleTriangles());
        assertEquals(0, frame.featureTriangles());
        assertTrue(mesh.lights().isEmpty());
        assertFalse(mesh.opacityMicromap().isEmpty());

        CpuClusterMesh.Segment segment = mesh.segments().getFirst();
        assertEquals(10.0F, segment.positions()[0]);
        assertEquals(20.0F, segment.positions()[1]);
        assertEquals(30.0F, segment.positions()[2]);
        int[] records = segment.primitiveRecords();
        for (int triangle = 0; triangle < 2; triangle++) {
            assertEquals(0, records[triangle * 8 + 4]);
            int flagsTexture = records[triangle * 8 + 5];
            assertEquals(7, PrimitivePacking.unpackDynamicTextureIndex(flagsTexture));
            assertTrue(PrimitivePacking.hasVisibleEmission(flagsTexture));
            assertEquals(
                    PrimitivePacking.NO_EMITTER_INDEX,
                    PrimitivePacking.unpackEmitterIndex(flagsTexture));
        }
    }

    @Test
    void sourceNormalsRepairDynamicTriangleWindingBeforeBlasSubmission() {
        DynamicMeshBuilder builder = new DynamicMeshBuilder(0.0, 0.0, 0.0);
        DynamicMeshBuilder.VertexSink sink = builder.open(
                VanillaSceneBoundary.Element.ENTITY,
                PrimitiveTopology.TRIANGLES,
                1,
                0);
        vertexNormal(sink, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.0F);
        vertexNormal(sink, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, -1.0F);
        vertexNormal(sink, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, -1.0F);
        sink.finish();

        CpuClusterMesh.Segment segment = builder.build(0, 0, 0, List.of())
                .mesh()
                .segments()
                .getFirst();
        float[] positions = segment.positions();
        float edgeOneX = positions[3] - positions[0];
        float edgeOneY = positions[4] - positions[1];
        float edgeTwoX = positions[6] - positions[0];
        float edgeTwoY = positions[7] - positions[1];

        assertTrue(edgeOneX * edgeTwoY - edgeOneY * edgeTwoX < 0.0F);
        assertEquals(0, segment.primitiveRecords()[4]);
    }

    @Test
    void capturesParticleTriangleWithoutInventingEmission() {
        DynamicMeshBuilder builder = new DynamicMeshBuilder(0.0, 0.0, 0.0);
        DynamicMeshBuilder.VertexSink sink = builder.open(
                VanillaSceneBoundary.Element.PARTICLE,
                PrimitiveTopology.TRIANGLES,
                3,
                0);
        vertex(sink, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        vertex(sink, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F);
        vertex(sink, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F);
        sink.finish();

        DynamicSceneFrame frame = builder.build(0, 0, 0, List.of());
        int flagsTexture =
                frame.mesh().segments().getFirst().primitiveRecords()[5];

        assertEquals(1, frame.particleTriangles());
        assertEquals(0, frame.featureTriangles());
        assertEquals(3, PrimitivePacking.unpackDynamicTextureIndex(flagsTexture));
        assertFalse(PrimitivePacking.hasVisibleEmission(flagsTexture));
        assertTrue(frame.mesh().lights().isEmpty());
    }

    @Test
    void exactReverseQuadsCollapseInsideOneMotionDomain() {
        DynamicMeshBuilder builder = new DynamicMeshBuilder(0.0, 0.0, 0.0);
        builder.beginMotionObject(VanillaSceneBoundary.Element.ENTITY, 17L);
        DynamicMeshBuilder.VertexSink sink = builder.open(
                VanillaSceneBoundary.Element.ENTITY,
                PrimitiveTopology.QUADS,
                2,
                0);
        vertexNormal(sink, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F);
        vertexNormal(sink, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F);
        vertexNormal(sink, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        vertexNormal(sink, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F);
        vertexNormal(sink, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.0F);
        vertexNormal(sink, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, -1.0F);
        vertexNormal(sink, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F, -1.0F);
        vertexNormal(sink, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, -1.0F);
        sink.finish();
        builder.endMotionObject(VanillaSceneBoundary.Element.ENTITY, 17L);

        DynamicSceneFrame frame = builder.build(0, 0, 0, List.of());

        assertEquals(2L, frame.mesh().triangleCount());
        assertEquals(2, frame.entityTriangles());
    }

    @Test
    void capturesTexturelessFeatureAsAnOwnedConstantMaterial() {
        DynamicMeshBuilder builder = new DynamicMeshBuilder(0.0, 0.0, 0.0);
        DynamicMeshBuilder.VertexSink sink = builder.openUntextured(
                VanillaSceneBoundary.Element.FEATURE,
                PrimitiveTopology.TRIANGLES,
                0);
        vertex(sink, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        vertex(sink, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        vertex(sink, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F);
        sink.finish();

        DynamicSceneFrame frame = builder.build(0, 0, 0, List.of());
        int[] primitive = frame.mesh().segments().getFirst().primitiveRecords();

        assertEquals(1, frame.featureTriangles());
        assertEquals(0, PrimitivePacking.unpackDynamicTextureIndex(primitive[5]));
        assertEquals(PrimitivePacking.CONSTANT_UV_DENSITY, primitive[6]);
        assertEquals(
                PrimitivePacking.CONSTANT_UV_OWN_TINT
                        | PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL,
                primitive[2]);
    }

    @Test
    void reportsUnsupportedNonTriangleTopology() {
        DynamicMeshBuilder builder = new DynamicMeshBuilder(0.0, 0.0, 0.0);
        DynamicMeshBuilder.VertexSink sink = builder.openUntextured(
                VanillaSceneBoundary.Element.FEATURE,
                PrimitiveTopology.LINES,
                0);
        vertex(sink, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        vertex(sink, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        sink.finish();

        DynamicSceneFrame frame = builder.build(0, 0, 0, List.of());

        assertTrue(frame.isEmpty());
        assertTrue(frame.compatibilityIssues().contains(
                DynamicSceneFrame.CompatibilityIssue.UNSUPPORTED_TOPOLOGY));
    }

    @Test
    void pairsReorderedEntitiesByStableIdentity() {
        DynamicSceneFrame previous = twoEntities(0.0F, 10.0F, false);
        DynamicSceneFrame current = twoEntities(20.0F, 5.0F, true);

        DynamicSceneMotion motion = DynamicSceneMotion.prepare(current, previous);

        float[] expected = new float[18];
        System.arraycopy(
                previous.mesh().segments().getFirst().positions(),
                9,
                expected,
                0,
                9);
        System.arraycopy(
                previous.mesh().segments().getFirst().positions(),
                0,
                expected,
                9,
                9);
        assertArrayEquals(expected, motion.previousPositions());
    }

    @Test
    void topologyChangeUsesZeroObjectMotionForLocalHistoryRejection() {
        DynamicSceneFrame previous = oneEntity(7L, 0.0F, 0.0F);
        DynamicSceneFrame current = oneEntity(7L, 3.0F, 0.25F);

        DynamicSceneMotion motion = DynamicSceneMotion.prepare(current, previous);

        assertArrayEquals(
                current.mesh().segments().getFirst().positions(),
                motion.previousPositions());
    }

    @Test
    void duplicateIdentityUsesZeroMotionWithoutDisablingValidObjects() {
        DynamicMeshBuilder previousBuilder =
                new DynamicMeshBuilder(0.0, 0.0, 0.0);
        entity(previousBuilder, 7L, 0.0F, 0.0F);
        entity(previousBuilder, 8L, 20.0F, 0.0F);
        DynamicSceneFrame previous =
                previousBuilder.build(0, 0, 0, List.of());

        DynamicMeshBuilder currentBuilder =
                new DynamicMeshBuilder(0.0, 0.0, 0.0);
        entity(currentBuilder, 7L, 2.0F, 0.0F);
        entity(currentBuilder, 7L, 12.0F, 0.0F);
        entity(currentBuilder, 8L, 25.0F, 0.0F);
        DynamicSceneFrame current =
                currentBuilder.build(0, 0, 0, List.of());

        assertEquals(1, current.motionSegments().size());
        assertEquals(8L, current.motionSegments().getFirst().key());
        assertTrue(current.compatibilityIssues().contains(
                DynamicSceneFrame.CompatibilityIssue.DUPLICATE_MOTION_IDENTITY));

        DynamicSceneMotion motion = DynamicSceneMotion.prepare(current, previous);
        float[] expected = current.mesh().segments().getFirst().positions().clone();
        System.arraycopy(
                previous.mesh().segments().getFirst().positions(),
                9,
                expected,
                18,
                9);
        assertArrayEquals(expected, motion.previousPositions());
    }

    @Test
    void emptyMotionObjectDoesNotCreateAnIdentityCollision() {
        DynamicMeshBuilder builder = new DynamicMeshBuilder(0.0, 0.0, 0.0);
        builder.beginMotionObject(VanillaSceneBoundary.Element.ENTITY, 7L);
        builder.endMotionObject(VanillaSceneBoundary.Element.ENTITY, 7L);
        entity(builder, 7L, 0.0F, 0.0F);

        DynamicSceneFrame frame = builder.build(0, 0, 0, List.of());

        assertEquals(1, frame.motionSegments().size());
        assertFalse(frame.compatibilityIssues().contains(
                DynamicSceneFrame.CompatibilityIssue.DUPLICATE_MOTION_IDENTITY));
    }

    @Test
    void removedEntityDoesNotRequireAFullFrameMotionPayload() {
        DynamicSceneFrame previous = oneEntity(7L, 0.0F, 0.0F);
        DynamicSceneFrame current = new DynamicMeshBuilder(0.0, 0.0, 0.0)
                .build(0, 0, 0, List.of());

        DynamicSceneMotion motion = DynamicSceneMotion.prepare(current, previous);

        assertEquals(0, motion.previousPositions().length);
    }

    @Test
    void particlesDoNotForcePerFrameHistoryResets() {
        DynamicMeshBuilder previousBuilder = new DynamicMeshBuilder(0.0, 0.0, 0.0);
        triangle(
                previousBuilder,
                VanillaSceneBoundary.Element.PARTICLE,
                0.0F,
                0.0F);
        DynamicMeshBuilder currentBuilder = new DynamicMeshBuilder(0.0, 0.0, 0.0);
        triangle(
                currentBuilder,
                VanillaSceneBoundary.Element.PARTICLE,
                4.0F,
                0.0F);

        DynamicSceneMotion motion = DynamicSceneMotion.prepare(
                currentBuilder.build(0, 0, 0, List.of()),
                previousBuilder.build(0, 0, 0, List.of()));

        assertArrayEquals(
                motion.frame().mesh().segments().getFirst().positions(),
                motion.previousPositions());
    }

    private static DynamicSceneFrame twoEntities(
            float firstX, float secondX, boolean reverse) {
        DynamicMeshBuilder builder = new DynamicMeshBuilder(0.0, 0.0, 0.0);
        if (reverse) {
            entity(builder, 2L, firstX, 0.0F);
            entity(builder, 1L, secondX, 0.0F);
        } else {
            entity(builder, 1L, firstX, 0.0F);
            entity(builder, 2L, secondX, 0.0F);
        }
        return builder.build(0, 0, 0, List.of());
    }

    private static DynamicSceneFrame oneEntity(long key, float x, float secondU) {
        DynamicMeshBuilder builder = new DynamicMeshBuilder(0.0, 0.0, 0.0);
        entity(builder, key, x, secondU);
        return builder.build(0, 0, 0, List.of());
    }

    private static void entity(
            DynamicMeshBuilder builder, long key, float x, float secondU) {
        builder.beginMotionObject(VanillaSceneBoundary.Element.ENTITY, key);
        triangle(builder, VanillaSceneBoundary.Element.ENTITY, x, secondU);
        builder.endMotionObject(VanillaSceneBoundary.Element.ENTITY, key);
    }

    private static void triangle(
            DynamicMeshBuilder builder,
            VanillaSceneBoundary.Element element,
            float x,
            float secondU) {
        DynamicMeshBuilder.VertexSink sink = builder.open(
                element,
                PrimitiveTopology.TRIANGLES,
                1,
                0);
        vertex(sink, x, 0.0F, 0.0F, 0.0F, 0.0F);
        vertex(sink, x + 1.0F, 0.0F, 0.0F, secondU, 0.0F);
        vertex(sink, x, 1.0F, 0.0F, 0.0F, 1.0F);
        sink.finish();
    }

    private static void vertex(
            DynamicMeshBuilder.VertexSink sink,
            float x,
            float y,
            float z,
            float u,
            float v) {
        sink.addVertex(x, y, z)
                .setColor(-1)
                .setUv(u, v)
                .setNormal(0.0F, 0.0F, 1.0F);
    }

    private static void vertexNormal(
            DynamicMeshBuilder.VertexSink sink,
            float x,
            float y,
            float z,
            float u,
            float v,
            float normalZ) {
        sink.addVertex(x, y, z)
                .setColor(-1)
                .setUv(u, v)
                .setNormal(0.0F, 0.0F, normalZ);
    }
}
