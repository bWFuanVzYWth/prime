// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.diagnostic.NrdInputView;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NrdInputDebugPassTest {
    @Test
    void reblurAndSigmaGridsExposeEveryInputAsAnUnmodifiedImage() {
        assertEquals(
                List.of(
                        radiance(0),
                        raw(1),
                        raw(2),
                        raw(3),
                        raw(4),
                        raw(5),
                        raw(6),
                        raw(7)),
                NrdInputDebugPass.primaryGrid());
        assertEquals(
                List.of(
                        radiance(0),
                        raw(8),
                        raw(9),
                        raw(10),
                        raw(11),
                        raw(12),
                        raw(13),
                        raw(14)),
                NrdInputDebugPass.reflectionGrid());
        assertEquals(
                List.of(radiance(0), raw(2), raw(3), raw(15)),
                NrdInputDebugPass.sigmaGrid());
    }

    @Test
    void fullscreenInputViewsUseTheSameRawImagesAsTheirGrids() {
        List<NrdInputView> views = List.of(
                NrdInputView.PRIMARY_MOTION,
                NrdInputView.PRIMARY_NORMAL_ROUGHNESS,
                NrdInputView.PRIMARY_VIEW_Z,
                NrdInputView.PRIMARY_DIFFUSE_SH0,
                NrdInputView.PRIMARY_DIFFUSE_SH1,
                NrdInputView.PRIMARY_SPECULAR_SH0,
                NrdInputView.PRIMARY_SPECULAR_SH1,
                NrdInputView.REFLECTION_MOTION,
                NrdInputView.REFLECTION_NORMAL_ROUGHNESS,
                NrdInputView.REFLECTION_VIEW_Z,
                NrdInputView.REFLECTION_DIFFUSE_SH0,
                NrdInputView.REFLECTION_DIFFUSE_SH1,
                NrdInputView.REFLECTION_SPECULAR_SH0,
                NrdInputView.REFLECTION_SPECULAR_SH1);
        for (int index = 0; index < views.size(); index++) {
            assertEquals(raw(index + 1), NrdInputDebugPass.descriptor(views.get(index)));
        }
        assertEquals(raw(2), NrdInputDebugPass.descriptor(
                NrdInputView.SIGMA_NORMAL_ROUGHNESS));
        assertEquals(raw(3), NrdInputDebugPass.descriptor(NrdInputView.SIGMA_VIEW_Z));
        assertEquals(raw(15), NrdInputDebugPass.descriptor(NrdInputView.SIGMA_PENUMBRA));
    }

    private static ImageDiagnosticPass.View raw(int source) {
        return new ImageDiagnosticPass.View(source, ImageDiagnosticPass.RAW);
    }

    private static ImageDiagnosticPass.View radiance(int source) {
        return new ImageDiagnosticPass.View(source, ImageDiagnosticPass.RADIANCE);
    }
}
