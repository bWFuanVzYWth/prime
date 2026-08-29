package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ClusterSceneTranslatorBoundaryTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsNullInputsAtThePublicTranslationBoundary(int missingArgument) {
        CapturedCluster captured = new CapturedCluster.Builder(0, 0, 0).build();
        ClusterTranslationSettings settings = new ClusterTranslationSettings(
                false,
                TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                true,
                VoxelSurfaceSettings.BASE_HEIGHT,
                false,
                false);

        CapturedCluster capturedArgument = missingArgument == 0 ? null : captured;
        LabPbrMaterialSet materialsArgument =
                missingArgument == 1 ? null : LabPbrMaterialSet.EMPTY;
        ClusterTranslationSettings settingsArgument = missingArgument == 2 ? null : settings;
        assertThrows(NullPointerException.class, () -> ClusterSceneTranslator.translate(
                capturedArgument, materialsArgument, settingsArgument));
    }
}
