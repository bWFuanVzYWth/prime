package dev.prime.render.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

final class RendererDataPrecisionContractTest {
    @Test
    void allAuthoredRg8NormalCodesRoundTripExactly() {
        for (int first = 0; first <= 255; first++) {
            for (int second = 0; second <= 255; second++) {
                float x = first / 255.0F;
                float y = second / 255.0F;
                assertEquals(first, Math.round(x * 255.0F));
                assertEquals(second, Math.round(y * 255.0F));
            }
        }
    }

    @Test
    void mediumIdentityIsIndependentOfContinuousExtinction() {
        Medium first = new Medium(17, 0.0F, 0.0F, 0.0F);
        Medium sameIdentityDifferentExtinction = new Medium(17, 0.1F, 0.2F, 0.3F);
        Medium differentIdentitySameExtinction = new Medium(18, 0.0F, 0.0F, 0.0F);

        assertEquals(first.id(), sameIdentityDifferentExtinction.id());
        assertNotEquals(first.id(), differentIdentitySameExtinction.id());
    }

    @Test
    void arbitraryWorldNormalsKeepTheCurrentF32BaselineWithoutOctahedralQuantization() {
        Random random = new Random(0x6e6f_726d_616cL);
        for (int iteration = 0; iteration < 4096; iteration++) {
            double x = random.nextDouble(-1.0, 1.0);
            double y = random.nextDouble(-1.0, 1.0);
            double z = random.nextDouble(-1.0, 1.0);
            double length = StrictMath.sqrt(x * x + y * y + z * z);
            if (length < 1.0e-12) {
                iteration--;
                continue;
            }
            float storedX = (float) (x / length);
            float storedY = (float) (y / length);
            float storedZ = (float) (z / length);
            double dot = storedX * x / length + storedY * y / length + storedZ * z / length;
            double angularError = StrictMath.acos(Math.clamp(dot, -1.0, 1.0));
            assertTrue(angularError <= 3.5e-4, "f32 world-normal baseline drifted");
        }
    }

    @Test
    void etaScaleFp16RemainsAnUnapprovedCandidateBecauseItChangesRouletteProbability() {
        RendererDataContracts.Semantic etaScale = RendererDataContracts.SEMANTICS.stream()
                .filter(value -> value.id().equals("RouletteEtaScale"))
                .findFirst()
                .orElseThrow();
        assertEquals("r32f-baseline", etaScale.minimumEncoding());

        boolean changed = false;
        for (int step = 1; step <= 8192; step++) {
            float value = 0.125F + step * (7.875F / 8192.0F);
            float quantized = Float.float16ToFloat(Float.floatToFloat16(value));
            float probability = Math.min(0.95F, 0.2F * value * value);
            float quantizedProbability = Math.min(0.95F, 0.2F * quantized * quantized);
            changed |= Float.floatToRawIntBits(probability)
                    != Float.floatToRawIntBits(quantizedProbability);
        }
        assertTrue(changed, "the candidate unexpectedly became distribution-exact");
        RendererDataContracts.Encoding candidate = RendererDataContracts.ENCODINGS.stream()
                .filter(value -> value.id().equals("rgba16f-candidate"))
                .findFirst()
                .orElseThrow();
        assertTrue(candidate.errorContract().startsWith("unapproved-"));
    }

    @Test
    void accumulatedFootprintsAndWorldDirectionsRemainAtTheirConservativeBaselines() {
        assertMinimumEncoding("LinearRec2020Radiance", "rgba32f-baseline");
        assertMinimumEncoding("LinearRec2020Reflectance", "rgb32f-baseline");
        assertMinimumEncoding("RayConeWidth", "r32f-baseline");
        assertMinimumEncoding("TextureLod", "r32f-baseline");
        assertMinimumEncoding("AreaDirectionWorld", "rgb32f-baseline");
        assertMinimumEncoding("ShDirectionWorld", "rgb32f-baseline");
    }

    private static void assertMinimumEncoding(String semantic, String expected) {
        assertEquals(
                expected,
                RendererDataContracts.SEMANTICS.stream()
                        .filter(value -> value.id().equals(semantic))
                        .findFirst()
                        .orElseThrow()
                        .minimumEncoding());
    }

    private record Medium(int id, float extinctionR, float extinctionG, float extinctionB) {}
}
