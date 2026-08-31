package dev.prime.render.post;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.vulkan.dlss.DlssRrProfile;
import org.junit.jupiter.api.Test;

final class ReconstructionProfilesTest {
    @Test
    void fiveFsrProfilesKeepTheirExactHistoricalScalarBits() {
        int[] widths = {3840, 2560, 2258, 1920, 1280};
        int[] heights = {2160, 1440, 1270, 1080, 720};
        int[] mipBits = {
            0xbf80_0000, 0xbfca_e00d, 0xbfe1_fd0b, 0xc000_0000, 0xc025_7007
        };
        int[] coneWidthBits = {
            0x3a21_d13a, 0x3a72_b9d6, 0x3a89_9bc0, 0x3aa1_d13a, 0x3af2_b9d6
        };
        int[] phases = {8, 18, 23, 32, 72};

        ReconstructionQualityMode[] qualities = ReconstructionQualityMode.values();
        for (int index = 0; index < qualities.length; index++) {
            ReconstructionQualityMode quality = qualities[index];
            assertEquals(widths[index], quality.renderExtent(3840, 2160).width());
            assertEquals(heights[index], quality.renderExtent(3840, 2160).height());
            assertEquals(mipBits[index], Float.floatToRawIntBits(quality.mipBias()));
            assertEquals(phases[index], quality.jitterPhaseCount());
            assertEquals(
                    coneWidthBits[index],
                    Float.floatToRawIntBits(quality.rayConeParameters(
                            1.25F,
                            1.5F,
                            widths[index],
                            heights[index]).width()));
            assertEquals(0x0000_0000, Float.floatToRawIntBits(quality.jitter(0).x()));
            assertEquals(0xbe2a_aaaa, Float.floatToRawIntBits(quality.jitter(0).y()));
        }
    }

    @Test
    void fiveDlssProfilesKeepNgxValuesAndLongCycleJitterBits() {
        int[] ngxValues = {5, 2, 1, 0, 3};
        int[] phaseCounts = {64, 64, 64, 64, 72};
        ReconstructionQualityMode[] qualities = ReconstructionQualityMode.values();
        for (int index = 0; index < qualities.length; index++) {
            ReconstructionQualityMode quality = qualities[index];
            assertEquals(ngxValues[index], DlssRrProfile.ngxPerfQualityValue(quality));
            assertEquals(phaseCounts[index], DlssRrProfile.jitterPhaseCount(quality));
            assertArrayEquals(
                    new int[] {0x0000_0000, 0xbe2a_aaaa},
                    new int[] {
                        Float.floatToRawIntBits(DlssRrProfile.jitter(quality, 0).x()),
                        Float.floatToRawIntBits(DlssRrProfile.jitter(quality, 0).y())
                    });
        }
    }
}
