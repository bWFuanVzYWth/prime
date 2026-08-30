package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CompiledClusterCodecTest {
    @Test
    void dynamicClustersCannotEnterTestReplayEncoding() {
        CompiledCluster dynamic = CompiledCluster.dynamic(
                0, 0, 0, CpuClusterMesh.empty(), new float[0]);

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.encode(dynamic));
    }

    @Test
    void roundTripPreservesSharedMacroPrimitiveLayout() {
        int[] primitive = {
            PrimitivePacking.packUv(0.0F, 0.0F),
            PrimitivePacking.packUv(1.0F, 0.0F),
            PrimitivePacking.packUv(0.0F, 1.0F),
            PrimitivePacking.packTintControl(PrimitivePacking.packTint(-1), 0),
            PrimitivePacking.packOctahedralUnitVector(0.0F, 0.0F, 1.0F),
            PrimitivePacking.packControlTexture(0, 1),
            Float.floatToRawIntBits(-1.0F),
            PrimitivePacking.packOctahedralUnitVector(1.0F, 0.0F, 0.0F)
        };
        CpuSectionMesh section = new CpuSectionMesh(
                new float[] {
                    0, 0, 0, 1, 0, 0, 1, 1, 0,
                    0, 0, 0, 1, 1, 0, 0, 1, 0
                },
                primitive,
                2,
                0,
                0,
                2,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
        CompiledCluster source = new CompiledCluster(
                0L, 0, 0, 0, CpuClusterMesh.fromSegments(List.of(section)));

        byte[] encoded = CompiledClusterCodec.encode(source);
        CpuClusterMesh decoded = CompiledClusterCodec.decode(encoded).mesh();

        assertArrayEquals(encoded, CompiledClusterCodec.encode(
                new CompiledCluster(0L, 0, 0, 0, decoded)));
        assertEquals(2L, decoded.opaqueMacroTriangleCount());
        assertEquals(1L, decoded.primitiveCount());
        assertEquals(CpuSectionMesh.PRIMITIVE_WORDS,
                decoded.segments().getFirst().primitiveRecords().length);
    }

    @Test
    void canonicalRoundTripPreservesTheCompleteUploadInput() {
        int flags = PrimitivePacking.encodeLegacySemantics(
                true, false, false, false, false, false);
        CpuSectionMesh section = new CpuSectionMesh(
                new float[] {
                    -0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F
                },
                new int[] {
                    PrimitivePacking.packUv(0.0F, 0.0F),
                    PrimitivePacking.packUv(1.0F, 0.0F),
                    PrimitivePacking.packUv(0.0F, 1.0F),
                    PrimitivePacking.packTintControl(
                            PrimitivePacking.packTint(-1), flags),
                    0,
                    PrimitivePacking.packControlTexture(flags, 1),
                    Float.floatToRawIntBits(1.0F),
                    PrimitivePacking.packOctahedralUnitVector(1.0F, 0.0F, 0.0F)
                },
                0,
                1,
                0,
                OpacityMicromapData.fullyUnknown(1),
                CpuSectionLights.EMPTY);
        CompiledCluster source = new CompiledCluster(
                0L, 0, 0, 0, CpuClusterMesh.fromSegments(List.of(section)));

        byte[] encoded = CompiledClusterCodec.encode(source);
        CompiledCluster decoded = CompiledClusterCodec.decode(encoded);

        assertArrayEquals(encoded, CompiledClusterCodec.encode(decoded));
        assertEquals(
                CompiledClusterFingerprint.sha256Hex(source),
                CompiledClusterFingerprint.sha256Hex(decoded));
        assertEquals(1L, decoded.mesh().cutoutTriangleCount());
        assertEquals(1, decoded.mesh().opacityMicromap().triangleCount());
    }

    @Test
    void staleMalformedOrTruncatedPayloadsFailBeforePublication() {
        CpuSectionMesh section = new CpuSectionMesh(
                new float[9],
                new int[CpuSectionMesh.PRIMITIVE_WORDS],
                1,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
        byte[] valid = CompiledClusterCodec.encode(new CompiledCluster(
                0L, 0, 0, 0, CpuClusterMesh.fromSegments(List.of(section))));
        byte[] stale = valid.clone();
        littleEndian(stale).putInt(4, 15);
        byte[] wrongMagic = valid.clone();
        wrongMagic[0] ^= 1;

        assertThrows(IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(stale));
        assertThrows(IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(wrongMagic));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(
                        Arrays.copyOf(valid, valid.length - 1)));
    }

    @Test
    void decodedUploadInputRejectsNonFiniteAndOutOfContractPrimitiveData() {
        CpuSectionMesh section = new CpuSectionMesh(
                new float[] {
                    0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F
                },
                new int[] {
                    PrimitivePacking.packUv(0.0F, 0.0F),
                    PrimitivePacking.packUv(1.0F, 0.0F),
                    PrimitivePacking.packUv(0.0F, 1.0F),
                    PrimitivePacking.packTintControl(
                            PrimitivePacking.packTint(-1), 0),
                    0,
                    PrimitivePacking.packControlTexture(0, 1),
                    Float.floatToRawIntBits(1.0F),
                    PrimitivePacking.packOctahedralUnitVector(1.0F, 0.0F, 0.0F)
                },
                1,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
        byte[] valid = CompiledClusterCodec.encode(new CompiledCluster(
                0L, 0, 0, 0, CpuClusterMesh.fromSegments(List.of(section))));
        PayloadOffsets offsets = firstPayloadOffsets(valid);

        byte[] nonFinitePosition = valid.clone();
        littleEndian(nonFinitePosition).putInt(
                offsets.positions(), Float.floatToRawIntBits(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(nonFinitePosition));

        byte[] invalidEmitter = valid.clone();
        littleEndian(invalidEmitter).putInt(
                offsets.primitives() + 5 * Integer.BYTES,
                PrimitivePacking.packControlEmitter(0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(invalidEmitter));

        byte[] nonzeroReserved = valid.clone();
        littleEndian(nonzeroReserved).putInt(
                offsets.primitives() + 4 * Integer.BYTES, 1);
        assertThrows(IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(nonzeroReserved));

        byte[] wrongCategory = valid.clone();
        int transmissive = PrimitivePacking.encodeLegacySemantics(
                false, false, true, false, false, false);
        littleEndian(wrongCategory).putInt(
                offsets.primitives() + 3 * Integer.BYTES,
                PrimitivePacking.packTintControl(
                        PrimitivePacking.packTint(-1), transmissive));
        littleEndian(wrongCategory).putInt(
                offsets.primitives() + 5 * Integer.BYTES,
                PrimitivePacking.packControlTexture(transmissive, 1));
        assertThrows(IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(wrongCategory));

        byte[] nonFiniteUvDensity = valid.clone();
        littleEndian(nonFiniteUvDensity).putInt(
                offsets.primitives() + 6 * Integer.BYTES,
                Float.floatToRawIntBits(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(nonFiniteUvDensity));
    }

    private static PayloadOffsets firstPayloadOffsets(byte[] encoded) {
        ByteBuffer input = littleEndian(encoded);
        input.position(56 + 6 * Integer.BYTES);
        int positionCount = input.getInt();
        int positions = input.position();
        input.position(Math.addExact(
                positions, Math.multiplyExact(positionCount, Float.BYTES)));
        int primitiveCount = input.getInt();
        if (positionCount != 9 || primitiveCount != CpuSectionMesh.PRIMITIVE_WORDS) {
            throw new AssertionError("Unexpected single-triangle fixture layout");
        }
        return new PayloadOffsets(positions, input.position());
    }

    private static ByteBuffer littleEndian(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    private record PayloadOffsets(int positions, int primitives) {
    }
}
