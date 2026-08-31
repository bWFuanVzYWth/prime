package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TextureTintUsageTest {
    @Test
    void followsStaticRelationAndVoxelInstanceMaterialIdentity() {
        int[] staticPrimitive = primitive(1, 0x0011_2233);
        int[] dynamicPrimitive = primitive(4, 0x00aa_bbcc);
        dynamicPrimitive[5] = PrimitivePacking.packDynamicControl(0, 1, false);
        int[] bakedPrimitive = primitive(5, 0x00dd_eeff);
        bakedPrimitive[2] = PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL;
        bakedPrimitive[6] = PrimitivePacking.CONSTANT_UV_DENSITY;
        int[] clusterPrimitives = concatenate(
                staticPrimitive, dynamicPrimitive, bakedPrimitive);
        int[] relation = {
            CpuSectionMesh.SURFACE_RELATION_BOUNDARY,
            PrimitivePacking.packUv(0.5F, 0.5F),
            0x0044_5566,
            2,
            1
        };
        int[] relations = SurfaceRelationTable.encode(
                Arrays.asList(relation, null, null));
        CpuClusterMesh.Segment segment = new CpuClusterMesh.Segment(
                new float[3 * 9], clusterPrimitives, relations, 3, 0, 0, 0, 0, 0);

        CpuVoxelMesh voxel = new CpuVoxelMesh(
                new float[9], primitive(3, 0), 1, 0, 0, OpacityMicromapData.EMPTY);
        CpuVoxelInstances instances = new CpuVoxelInstances(
                new int[] {0, 0},
                new int[] {0x0001_0203, 0x0004_0506},
                new float[6]);
        CpuClusterMesh mesh = CpuClusterMesh.fromEncoded(
                List.of(segment),
                3,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.EMPTY,
                List.of(voxel),
                instances);

        TextureTintUsage usage = TextureTintUsage.measure(mesh);

        assertEquals(4, usage.pairReferences().size());
        assertEquals(3, usage.textureIds().size());
        assertEquals(4, usage.packedTints().size());
        assertEquals(4L, usage.pairReferenceCount());
        assertEquals(1L, usage.staticSurfaceReferences());
        assertEquals(1L, usage.relationMaterialReferences());
        assertEquals(0L, usage.lightEmitterReferences());
        assertEquals(2L, usage.voxelSurfaceReferences());
        assertEquals(1L, usage.dynamicReferences());
        assertEquals(1L, usage.bakedReferences());
        assertEquals(
                1L,
                usage.pairReferences().get(
                        new TextureTintUsage.Pair(1, 0x0011_2233)));
        assertEquals(
                1L,
                usage.pairReferences().get(
                        new TextureTintUsage.Pair(2, 0x0044_5566)));
    }

    @Test
    void residencyCombinationAndTemporalObservationHaveDifferentCounts() {
        CpuClusterMesh mesh = CpuClusterMesh.fromEncoded(
                List.of(new CpuClusterMesh.Segment(
                        new float[9], primitive(7, 0x0012_3456), 1, 0, 0)),
                1,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.EMPTY);
        TextureTintUsage usage = TextureTintUsage.measure(mesh);

        TextureTintUsage resident = TextureTintUsage.combine(List.of(usage, usage));
        TextureTintUsage observed = usage.observedUnion(usage);

        assertEquals(2L, resident.pairReferenceCount());
        assertEquals(2L, resident.staticSurfaceReferences());
        assertEquals(1L, observed.pairReferenceCount());
        assertEquals(1L, observed.staticSurfaceReferences());
    }

    private static int[] primitive(int textureId, int tint) {
        int[] result = new int[CpuSectionMesh.PRIMITIVE_WORDS];
        result[3] = PrimitivePacking.packTintControl(tint, 0);
        result[5] = PrimitivePacking.packControlTexture(0, textureId);
        result[6] = Float.floatToRawIntBits(1.0F);
        return result;
    }

    private static int[] concatenate(int[]... values) {
        int[] result = new int[values.length * CpuSectionMesh.PRIMITIVE_WORDS];
        int cursor = 0;
        for (int[] value : values) {
            System.arraycopy(value, 0, result, cursor, value.length);
            cursor += value.length;
        }
        return result;
    }
}
