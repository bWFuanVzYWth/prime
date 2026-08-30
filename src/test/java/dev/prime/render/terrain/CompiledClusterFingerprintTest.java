package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CompiledClusterFingerprintTest {
    @Test
    void equivalentOwnedPayloadsHaveTheSameCanonicalIdentity() {
        CompiledCluster first = cluster(
                new float[] {0.0F, -0.0F, 1.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F},
                opaquePrimitive(8));
        CompiledCluster second = cluster(
                Arrays.copyOf(first.mesh().segments().get(0).positions(), 9),
                Arrays.copyOf(first.mesh().segments().get(0).primitiveRecords(), 8));

        String firstHash = CompiledClusterFingerprint.sha256Hex(first);
        assertEquals(64, firstHash.length());
        assertEquals(firstHash, CompiledClusterFingerprint.sha256Hex(first));
        assertEquals(firstHash, CompiledClusterFingerprint.sha256Hex(second));
    }

    @Test
    void rawFloatBitsAndPrimitivePayloadParticipateInIdentity() {
        float[] positions =
                {0.0F, -0.0F, 1.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F};
        int[] primitives = opaquePrimitive(8);
        String baseline = CompiledClusterFingerprint.sha256Hex(
                cluster(positions, primitives));

        float[] changedFloat = Arrays.copyOf(positions, positions.length);
        changedFloat[0] = -0.0F;
        int[] changedPrimitive = Arrays.copyOf(primitives, primitives.length);
        changedPrimitive[7] ^= 1;

        assertNotEquals(
                baseline,
                CompiledClusterFingerprint.sha256Hex(
                        cluster(changedFloat, primitives)));
        assertNotEquals(
                baseline,
                CompiledClusterFingerprint.sha256Hex(
                        cluster(positions, changedPrimitive)));
    }

    private static CompiledCluster cluster(float[] positions, int[] primitives) {
        CpuSectionMesh section = new CpuSectionMesh(
                positions,
                primitives,
                1,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
        return new CompiledCluster(
                0L,
                0,
                0,
                0,
                CpuClusterMesh.fromSegments(List.of(section)));
    }

    private static int[] opaquePrimitive(int tangentMarker) {
        return new int[] {
            PrimitivePacking.packUv(0.0F, 0.0F),
            PrimitivePacking.packUv(1.0F, 0.0F),
            PrimitivePacking.packUv(0.0F, 1.0F),
            PrimitivePacking.packTintControl(PrimitivePacking.packTint(-1), 0),
            PrimitivePacking.packOctahedralUnitVector(0.0F, 0.0F, 1.0F),
            PrimitivePacking.packControlTexture(0, 1),
            Float.floatToRawIntBits(1.0F),
            tangentMarker
        };
    }
}
