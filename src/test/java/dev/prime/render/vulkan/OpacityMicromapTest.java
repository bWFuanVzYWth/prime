// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import dev.prime.render.terrain.OpacityMicromapData;
import org.junit.jupiter.api.Test;

final class OpacityMicromapTest {
    @Test
    void triangleDescriptorsPreserveMixedFormatsAndVariableOffsets() {
        int packedTwoState = OpacityMicromapData.SUBDIVISION_LEVEL
                | OpacityMicromapData.TWO_STATE_FORMAT << 16;
        int packedFourState = OpacityMicromapData.SUBDIVISION_LEVEL
                | OpacityMicromapData.FOUR_STATE_FORMAT << 16;
        assertArrayEquals(
                new int[] {
                    0,
                    packedTwoState,
                    OpacityMicromapData.TWO_STATE_BYTES_PER_BLOCK,
                    packedFourState
                },
                OpacityMicromap.triangleDescriptors(
                        new int[] {0, OpacityMicromapData.TWO_STATE_BYTES_PER_BLOCK},
                        new int[] {
                            OpacityMicromapData.TWO_STATE_FORMAT,
                            OpacityMicromapData.FOUR_STATE_FORMAT
                        },
                        new int[] {
                            OpacityMicromapData.SUBDIVISION_LEVEL,
                            OpacityMicromapData.SUBDIVISION_LEVEL
                        }));
    }

    @Test
    void triangleDescriptorsPreservePerBlockSubdivisionLevels() {
        assertArrayEquals(
                new int[] {
                    0,
                    5 | OpacityMicromapData.TWO_STATE_FORMAT << 16,
                    128,
                    6 | OpacityMicromapData.FOUR_STATE_FORMAT << 16
                },
                OpacityMicromap.triangleDescriptors(
                        new int[] {0, 128},
                        new int[] {
                            OpacityMicromapData.TWO_STATE_FORMAT,
                            OpacityMicromapData.FOUR_STATE_FORMAT
                        },
                        new int[] {5, 6}));
    }
}
