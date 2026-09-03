package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import dev.prime.render.scene.CapturedSectionGeometry;
import org.junit.jupiter.api.Test;

final class ClusterSceneTranslatorDeterminismTest {
    @Test
    void fixedSectionSlotsMakeCaptureCompletionOrderIrrelevant() {
        TestSprite base = new TestSprite("ordered_base");
        TestSprite overlay = new TestSprite("ordered_overlay");
        base.fill(0xff60_4020);
        overlay.fill(0xff40_a040);
        CapturedSectionGeometry section = ClusterSceneTranslatorTest.capturedLayeredFace(
                base,
                overlay,
                2.0F,
                new int[] {-1, -1, -1, -1});
        CapturedCluster.Builder forward = new CapturedCluster.Builder(0, 0, 0);
        forward.add(0, 0, 0, section);
        forward.add(1, 0, 0, section);
        CapturedCluster.Builder reverse = new CapturedCluster.Builder(0, 0, 0);
        reverse.add(1, 0, 0, section);
        reverse.add(0, 0, 0, section);

        CpuClusterMesh first = ClusterSceneTranslatorTest.translate(forward.build());
        CpuClusterMesh second = ClusterSceneTranslatorTest.translate(reverse.build());

        assertArrayEquals(
                first.voxelInstances().meshIndices(),
                second.voxelInstances().meshIndices());
        assertArrayEquals(
                first.voxelInstances().packedTints(),
                second.voxelInstances().packedTints());
        assertArrayEquals(
                first.voxelInstances().translations(),
                second.voxelInstances().translations());
        assertArrayEquals(
                first.voxelMeshes().getFirst().geometry().primitiveRecords(),
                second.voxelMeshes().getFirst().geometry().primitiveRecords());
    }
}
