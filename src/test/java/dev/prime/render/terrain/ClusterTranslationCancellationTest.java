package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClusterTranslationCancellationTest {
    private static final CapturedSprite FIRST = sprite("cancel_first", 1);
    private static final CapturedSprite SECOND = sprite("cancel_second", 2);

    @Test
    void hotLoopsPollAtMost1024WorkItemsApart() {
        CountingControl control = new CountingControl();
        ClusterTranslationWork work = new ClusterTranslationWork(control);

        for (int index = 0; index < 1023; index++) {
            work.step();
        }
        assertEquals(0, control.checkpoints);
        work.step();
        assertEquals(1, control.checkpoints);
        for (int index = 0; index < 1024; index++) {
            work.step();
        }
        assertEquals(2, control.checkpoints);
    }

    @Test
    void overlayPairingCancellationPropagatesAndTheInputCanReplay() {
        assertResolverCancellation(overlayFixture(), true, 1);
    }

    @Test
    void boundaryCellCancellationPropagatesAndTheInputCanReplay() {
        assertResolverCancellation(fragmentedBoundaryFixture(true), true, 1);
    }

    @Test
    void coalesceCancellationPropagatesAndTheInputCanReplay() {
        assertResolverCancellation(fragmentedBoundaryFixture(false), true, 1);
    }

    @Test
    void meshBuildCancellationPropagatesAndTheGeometryCanReplay() {
        CpuSectionGeometry geometry = mergeGeometry();
        CountingControl count = new CountingControl();
        CpuClusterMesh baseline = buildMesh(geometry, count);
        assertTrue(count.checkpoints > 2);
        CancellationMarker marker = new CancellationMarker();
        CancelAt control = new CancelAt(count.checkpoints - 1, marker);

        CancellationMarker thrown = assertThrows(
                CancellationMarker.class, () -> buildMesh(geometry, control));

        assertSame(marker, thrown);
        assertMeshEquals(baseline, buildMesh(geometry, ClusterTranslationControl.UNINTERRUPTIBLE));
    }

    private static void assertResolverCancellation(
            CapturedCluster cluster, boolean resolveStaticOverlays, int checkpointsFromEnd) {
        CountingControl count = new CountingControl();
        TransparentBoundaryResolver.Result baseline = TransparentBoundaryResolver.resolve(
                cluster,
                resolveStaticOverlays,
                new ClusterTranslationWork(count));
        int target = count.checkpoints - checkpointsFromEnd;
        assertTrue(target > 0, "fixture did not exercise cancellable hot work");
        CancellationMarker marker = new CancellationMarker();
        CancelAt control = new CancelAt(target, marker);

        CancellationMarker thrown = assertThrows(
                CancellationMarker.class,
                () -> TransparentBoundaryResolver.resolve(
                        cluster,
                        resolveStaticOverlays,
                        new ClusterTranslationWork(control)));

        assertSame(marker, thrown);
        TransparentBoundaryResolver.Result replay = TransparentBoundaryResolver.resolve(
                cluster, resolveStaticOverlays);
        assertEquals(snapshot(baseline), snapshot(replay));
    }

    private static CapturedCluster overlayFixture() {
        CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
        CapturedSectionGeometry.Surface base = surface(
                FIRST,
                CapturedSectionGeometry.Layer.OPAQUE,
                false,
                null,
                0xff80_a0c0);
        CapturedSectionGeometry.Surface overlay = surface(
                SECOND,
                CapturedSectionGeometry.Layer.CUTOUT,
                true,
                null,
                0xff80_a0c0);
        for (int index = 0; index < 384; index++) {
            section.add(unitFace(index + 0.25F, 1.0F), base);
        }
        for (int index = 0; index < 384; index++) {
            section.add(unitFace(index + 0.25F, 1.0F), overlay);
        }
        return cluster(section.build());
    }

    private static CapturedCluster fragmentedBoundaryFixture(boolean sameTransmissiveMedium) {
        CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
        CapturedSectionGeometry.Layer layer = sameTransmissiveMedium
                ? CapturedSectionGeometry.Layer.TRANSLUCENT
                : CapturedSectionGeometry.Layer.OPAQUE;
        CapturedSectionGeometry.Surface positive = surface(
                FIRST,
                layer,
                false,
                new CapturedSectionGeometry.BlockFacts(-1, 0, 0, 1),
                0xff80_a0c0);
        CapturedSectionGeometry.Surface negative = surface(
                sameTransmissiveMedium ? FIRST : SECOND,
                layer,
                false,
                new CapturedSectionGeometry.BlockFacts(0, 0, 0, 1),
                0xff80_a0c0);
        for (int strip = 0; strip < 32; strip++) {
            float minimum = strip / 32.0F;
            float maximum = (strip + 1) / 32.0F;
            section.add(rectangleFace(0.0F, 1.0F, minimum, maximum, 0.0F, 1.0F), positive);
            section.add(rectangleFace(0.0F, -1.0F, 0.0F, 1.0F, minimum, maximum), negative);
        }
        return cluster(section.build());
    }

    private static CpuSectionGeometry mergeGeometry() {
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false, 1024, 2, 2);
        SectionMeshAccumulator.Surface surface = new SectionMeshAccumulator.Surface().set(
                -1, false, false, false, false, false, false, true, 0, FIRST);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                accumulator.addQuad(
                        SectionMeshAccumulatorTest.horizontalQuad(x, y, 0.0F, 1.0F),
                        surface);
            }
        }
        return accumulator.build();
    }

    private static CpuClusterMesh buildMesh(
            CpuSectionGeometry geometry, ClusterTranslationControl control) {
        SectionClusterMeshBuilder builder = new SectionClusterMeshBuilder(
                0,
                0,
                0,
                1024,
                2,
                2,
                false,
                0.0F,
                new ClusterTranslationWork(control));
        builder.add(0, 0, 0, geometry);
        return builder.build();
    }

    private static List<String> snapshot(TransparentBoundaryResolver.Result result) {
        ArrayList<String> output = new ArrayList<>();
        SectionMeshAccumulator.Quad geometry = new SectionMeshAccumulator.Quad();
        for (int section = 0; section < SectionCluster.SECTION_COUNT; section++) {
            for (TransparentBoundaryResolver.ResolvedQuad resolved : result.section(section)) {
                resolved.write(geometry);
                StringBuilder value = new StringBuilder().append(section).append(':');
                for (int vertex = 0; vertex < 4; vertex++) {
                    value.append(Float.floatToIntBits(geometry.x[vertex])).append(',')
                            .append(Float.floatToIntBits(geometry.y[vertex])).append(',')
                            .append(Float.floatToIntBits(geometry.z[vertex])).append(',')
                            .append(Float.floatToIntBits(geometry.u[vertex])).append(',')
                            .append(Float.floatToIntBits(geometry.v[vertex])).append(';');
                }
                appendDefinition(value, resolved.definition());
                output.add(value.toString());
            }
        }
        return List.copyOf(output);
    }

    private static void appendDefinition(StringBuilder output, SurfaceDefinition definition) {
        output.append(definition.interfaceMode()).append(':');
        appendBinding(output, definition.primary());
        if (definition instanceof SurfaceDefinition.Overlay overlay) {
            appendBinding(output, overlay.secondary());
            output.append(overlay.positiveOnly());
        } else if (definition instanceof SurfaceDefinition.Bilateral bilateral) {
            appendBinding(output, bilateral.secondary());
        } else if (definition instanceof SurfaceDefinition.Boundary boundary) {
            appendMedium(output, boundary.positiveMedium());
            appendMedium(output, boundary.negativeMedium());
        }
    }

    private static void appendBinding(
            StringBuilder output, SurfaceDefinition.MaterialBinding binding) {
        output.append(binding.surface().sprite().id())
                .append('/')
                .append(binding.transmissiveTopology())
                .append('/');
        for (int vertex = 0; vertex < 4; vertex++) {
            output.append(Float.floatToIntBits(binding.uv().u(vertex)))
                    .append(',')
                    .append(Float.floatToIntBits(binding.uv().v(vertex)))
                    .append(';');
        }
    }

    private static void appendMedium(
            StringBuilder output, SurfaceDefinition.MediumEndpoint endpoint) {
        output.append(endpoint.surface().sprite().id())
                .append('/')
                .append(endpoint.transmissiveTopology())
                .append('/')
                .append(Float.floatToIntBits(endpoint.referenceU()))
                .append('/')
                .append(Float.floatToIntBits(endpoint.referenceV()));
    }

    private static void assertMeshEquals(CpuClusterMesh expected, CpuClusterMesh actual) {
        assertEquals(expected.triangleCount(), actual.triangleCount());
        assertEquals(expected.segments().size(), actual.segments().size());
        for (int index = 0; index < expected.segments().size(); index++) {
            CpuClusterMesh.Segment left = expected.segments().get(index);
            CpuClusterMesh.Segment right = actual.segments().get(index);
            org.junit.jupiter.api.Assertions.assertArrayEquals(left.positions(), right.positions());
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    left.primitiveRecords(), right.primitiveRecords());
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    left.surfaceRelationRecords(), right.surfaceRelationRecords());
        }
    }

    private static CapturedSectionGeometry.Surface surface(
            CapturedSprite sprite,
            CapturedSectionGeometry.Layer layer,
            boolean rasterOverlay,
            CapturedSectionGeometry.BlockFacts block,
            int color) {
        return CapturedSectionGeometry.Surface.uniform(
                color,
                layer,
                false,
                false,
                false,
                false,
                false,
                true,
                rasterOverlay,
                0,
                sprite,
                block);
    }

    private static CapturedCluster cluster(CapturedSectionGeometry section) {
        CapturedCluster.Builder cluster = new CapturedCluster.Builder(0, 0, 0);
        cluster.add(0, 0, 0, section);
        return cluster.build();
    }

    private static CapturedSectionGeometry.MutableQuad unitFace(float plane, float normal) {
        return rectangleFace(plane, normal, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    private static CapturedSectionGeometry.MutableQuad rectangleFace(
            float plane,
            float normal,
            float minimumY,
            float maximumY,
            float minimumZ,
            float maximumZ) {
        float[] y = normal > 0.0F
                ? new float[] {minimumY, maximumY, maximumY, minimumY}
                : new float[] {minimumY, minimumY, maximumY, maximumY};
        float[] z = normal > 0.0F
                ? new float[] {minimumZ, minimumZ, maximumZ, maximumZ}
                : new float[] {minimumZ, maximumZ, maximumZ, minimumZ};
        CapturedSectionGeometry.MutableQuad quad = new CapturedSectionGeometry.MutableQuad();
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = plane;
            quad.y[vertex] = y[vertex];
            quad.z[vertex] = z[vertex];
            quad.u[vertex] = (y[vertex] - minimumY) / (maximumY - minimumY);
            quad.v[vertex] = (z[vertex] - minimumZ) / (maximumZ - minimumZ);
        }
        quad.normalX = normal;
        return quad;
    }

    private static CapturedSprite sprite(String path, int textureId) {
        return new CapturedSprite(
                new SpriteId("prime", path),
                textureId,
                16,
                16,
                false,
                new int[] {0},
                null);
    }

    private static final class CountingControl implements ClusterTranslationControl {
        private int checkpoints;

        @Override
        public void checkpoint() {
            this.checkpoints++;
        }
    }

    private static final class CancelAt implements ClusterTranslationControl {
        private final int target;
        private final CancellationMarker marker;
        private int checkpoints;

        CancelAt(int target, CancellationMarker marker) {
            this.target = target;
            this.marker = marker;
        }

        @Override
        public void checkpoint() {
            if (++this.checkpoints == this.target) {
                throw this.marker;
            }
        }
    }

    private static final class CancellationMarker extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
