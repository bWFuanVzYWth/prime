package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.material.ScatteringFamily;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GpuSurfaceRelationTableTest {
    private static final int GLASS_CONTROL = ScatteringFamily.DIELECTRIC_SOLID.encoded()
            << PrimitivePacking.CONTROL_SCATTERING_SHIFT;

    @Test
    void boundaryFactsCollapseToOneMaterialIdentityWhileOtherRelationsStayUnchanged() {
        int identity = MaterialIdResolver.pack(23, 53);
        int[] boundary = boundary(identity);
        int[] overlay = overlay();
        int[] source = SurfaceRelationTable.encode(Arrays.asList(boundary, null, overlay));

        int[] encoded = GpuSurfaceRelationTable.encodeResolved(source, 3);

        assertEquals(source.length - 1, encoded.length);
        assertEquals(3, encoded[0]);
        assertEquals(0, encoded[1]);
        assertEquals(7, encoded[2]);
        assertArrayEquals(
                new int[] {
                    CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                            | CpuSectionMesh.SURFACE_RELATION_MICRO_GAP_ELIGIBLE,
                    boundary[1],
                    boundary[2],
                    identity
                },
                GpuSurfaceRelationTable.record(encoded, 3, 0));
        assertArrayEquals(
                overlay,
                GpuSurfaceRelationTable.record(encoded, 3, 2));

        CpuSectionMesh section = new CpuSectionMesh(
                new float[3 * 9],
                new int[3 * CpuSectionMesh.PRIMITIVE_WORDS],
                source,
                3,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
        CpuClusterMesh mesh = CpuClusterMesh.fromSegments(List.of(section));
        assertEquals(68L, mesh.surfaceRelationBytes());
        assertEquals(64L, GpuSurfaceRelationTable.byteSize(mesh));
    }

    @Test
    void boundaryEncodingRejectsAnUnresolvedMaterialIdentity() {
        int[] source = SurfaceRelationTable.encode(List.of(
                boundary(MaterialIdResolver.pack(23, 0))));

        assertThrows(
                IllegalArgumentException.class,
                () -> GpuSurfaceRelationTable.encodeResolved(source, 1));
    }

    private static int[] boundary(int identity) {
        return new int[] {
            CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                    | CpuSectionMesh.SURFACE_RELATION_MICRO_GAP_ELIGIBLE
                    | GLASS_CONTROL << 8,
            PrimitivePacking.packUv(0.25F, 0.75F),
            0x0012_3456,
            7,
            identity
        };
    }

    private static int[] overlay() {
        int[] result = new int[9];
        result[0] = CpuSectionMesh.SURFACE_RELATION_OVERLAY
                | PrimitivePacking.CONTROL_ALPHA_CUTOUT << 8;
        result[4] = PrimitivePacking.packTintControl(0x00ff_ffff, 0);
        result[6] = PrimitivePacking.packControlTexture(0, 9);
        result[7] = Float.floatToRawIntBits(1.0F);
        return result;
    }
}
