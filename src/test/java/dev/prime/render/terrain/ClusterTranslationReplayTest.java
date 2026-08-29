package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.material.BuiltinMaterialClass;
import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import dev.prime.render.scene.SpritePixelView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

final class ClusterTranslationReplayTest {
    @Test
    void roundTripPreservesInputBehaviorAndEncoding() throws IOException {
        ClusterTranslationInput source = populatedInput();
        ClusterTranslationReplay.Metadata metadata =
                ClusterTranslationReplay.Metadata.failure(
                        ClusterTranslationReplay.Outcome.FAILED,
                        42L,
                        new IllegalArgumentException("bad input"));

        byte[] encoded = encode(source, metadata);
        ClusterTranslationReplay.Decoded decoded = ClusterTranslationReplay.read(
                new ByteArrayInputStream(encoded));

        assertEquals(metadata, decoded.metadata());
        assertArrayEquals(encoded, encode(decoded.input(), decoded.metadata()));
        assertMeshEquals(
                ClusterSceneTranslator.translate(source),
                ClusterSceneTranslator.translate(decoded.input()));
        CapturedSectionGeometry section = decoded.input().captured().section(0);
        assertNotNull(section);
        CapturedSectionGeometry.Surface surface = section.quads().getFirst().surface();
        assertEquals(new CapturedSectionGeometry.FluidFacts(1, 2, 3, true, 0x15), surface.fluid());
        assertEquals(new CapturedSectionGeometry.BlockFacts(0, 0, 0, 7), surface.block());
        assertEquals(BuiltinMaterialClass.COPPER, surface.builtinMaterialClass());
        assertTrue(section.quads().getFirst().peerOnly());
    }

    @Test
    void recordsOnlyTheMaterialSubsetUsedByCapturedQuads() throws IOException {
        ClusterTranslationReplay.Decoded decoded = ClusterTranslationReplay.read(
                new ByteArrayInputStream(encode(
                        populatedInput(), ClusterTranslationReplay.Metadata.success(1L))));
        LabPbrMaterialSet materials = decoded.input().materials();

        assertEquals(Set.of(new SpriteId("prime", "used")), materials.textureIds().keySet());
        assertEquals(Set.of(new SpriteId("prime", "used")), materials.normalSprites());
        assertEquals(Set.of(new SpriteId("prime", "used")), materials.specularSprites());
        assertEquals(Set.of(new SpriteId("prime", "used")), materials.emissionMaps().keySet());
        assertEquals(Set.of(new SpriteId("prime", "used")), materials.heightMaps().keySet());
        assertEquals(Set.of(new SpriteId("prime", "used")), materials.materialMaps().keySet());
        assertFalse(materials.textureIds().containsKey(new SpriteId("prime", "unused")));
    }

    @Test
    void readsTheCheckedInV1CompatibilityResource() throws IOException {
        byte[] encoded;
        try (var resource = getClass().getResourceAsStream(
                "/replays/cluster-translation-empty-v1.ptr.gz.b64")) {
            assertNotNull(resource);
            encoded = Base64.getMimeDecoder().decode(resource.readAllBytes());
        }

        ClusterTranslationReplay.Decoded decoded = ClusterTranslationReplay.read(
                new ByteArrayInputStream(encoded));

        assertEquals(ClusterTranslationReplay.Outcome.SUCCESS, decoded.metadata().outcome());
        assertEquals(0L, ClusterSceneTranslator.translate(decoded.input()).triangleCount());
    }

    @Test
    void rejectsWrongMagicOldVersionIllegalEnumAndOutOfRangeCount() throws IOException {
        byte[] raw = uncompressed(emptyEncoding());
        byte[] wrongMagic = raw.clone();
        wrongMagic[0] ^= 1;
        byte[] oldVersion = raw.clone();
        ByteBuffer.wrap(oldVersion).putInt(8, 0);
        byte[] illegalOutcome = raw.clone();
        illegalOutcome[20] = (byte) 0xff;
        byte[] excessiveSpriteCount = raw.clone();
        ByteBuffer.wrap(excessiveSpriteCount).putInt(69, Integer.MAX_VALUE);

        assertMalformed(compressed(wrongMagic));
        assertMalformed(compressed(oldVersion));
        assertMalformed(compressed(illegalOutcome));
        assertMalformed(compressed(excessiveSpriteCount));
    }

    @Test
    void rejectsTruncationAndTrailingDecodedData() throws IOException {
        byte[] encoded = emptyEncoding();
        assertMalformed(Arrays.copyOf(encoded, encoded.length / 2));

        byte[] raw = uncompressed(encoded);
        byte[] trailing = Arrays.copyOf(raw, raw.length + 1);
        trailing[raw.length] = 1;
        assertMalformed(compressed(trailing));
    }

    private static ClusterTranslationInput populatedInput() {
        SpriteId usedId = new SpriteId("prime", "used");
        SpriteId unusedId = new SpriteId("prime", "unused");
        CapturedSprite sprite = new CapturedSprite(
                usedId,
                5,
                2,
                2,
                true,
                new int[] {1, 0},
                new ArrayPixels(4, 2, new int[] {
                    0xff10_2030, 0xff40_5060, 0xff70_8090, 0xffa0_b0c0,
                    0xff01_0203, 0xff04_0506, 0xff07_0809, 0xff0a_0b0c
                }));
        CapturedSectionGeometry.MutableQuad quad = new CapturedSectionGeometry.MutableQuad();
        float[] y = {0.0F, 1.0F, 1.0F, 0.0F};
        float[] z = {0.0F, 0.0F, 1.0F, 1.0F};
        float[] u = {0.0F, 1.0F, 1.0F, 0.0F};
        float[] v = {0.0F, 0.0F, 1.0F, 1.0F};
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = 0.0F;
            quad.y[vertex] = y[vertex];
            quad.z[vertex] = z[vertex];
            quad.u[vertex] = u[vertex];
            quad.v[vertex] = v[vertex];
        }
        quad.normalX = -1.0F;
        CapturedSectionGeometry.Surface surface = new CapturedSectionGeometry.Surface(
                0xff11_2233,
                0xff44_5566,
                0xff77_8899,
                0xffaa_bbcc,
                CapturedSectionGeometry.Layer.TRANSLUCENT,
                false,
                false,
                true,
                true,
                false,
                false,
                false,
                7,
                sprite,
                new CapturedSectionGeometry.FluidFacts(1, 2, 3, true, 0x15),
                new CapturedSectionGeometry.BlockFacts(0, 0, 0, 7),
                BuiltinMaterialClass.COPPER);
        CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
        section.addPeer(quad, surface);
        CapturedCluster.Builder cluster = new CapturedCluster.Builder(0, 0, 0);
        cluster.add(0, 0, 0, section.build());

        int[] normal = {
            0x1011_1213, 0x2021_2223, 0x3031_3233, 0x4041_4243,
            0x5051_5253, 0x6061_6263, 0x7071_7273, 0x8081_8283
        };
        int[] specular = {
            0x0011_2233, 0x1011_2233, 0x2011_2233, 0x3011_2233,
            0x4011_2233, 0x5011_2233, 0x6011_2233, 0x7011_2233
        };
        LabPbrEmissionMap emission = LabPbrEmissionMap.fromSpecular(
                specular, 4, 2, 2, 2, 2, 2);
        LabPbrHeightMap height = LabPbrHeightMap.fromNormal(
                normal, 4, 2, 2, 2, 2, 2);
        LabPbrMaterialMap material = new LabPbrMaterialMap(
                new LabPbrMaterialMap.Pixels(normal, 4, 2, 2, 2, 2),
                new LabPbrMaterialMap.Pixels(specular, 4, 2, 2, 2, 2));
        LabPbrMaterialSet materials = new LabPbrMaterialSet(
                Map.of(usedId, 5, unusedId, 6),
                Set.of(usedId, unusedId),
                Set.of(usedId, unusedId),
                Map.of(usedId, emission, unusedId, emission),
                Map.of(usedId, height, unusedId, height),
                Map.of(usedId, material, unusedId, material));
        return new ClusterTranslationInput(cluster.build(), materials, settings());
    }

    private static ClusterTranslationSettings settings() {
        return new ClusterTranslationSettings(
                false,
                TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                OpacityMicromapData.SUBDIVISION_LEVEL,
                false,
                VoxelSurfaceSettings.BASE_HEIGHT,
                true,
                false);
    }

    private static byte[] emptyEncoding() throws IOException {
        ClusterTranslationInput input = new ClusterTranslationInput(
                new CapturedCluster.Builder(0, 0, 0).build(),
                LabPbrMaterialSet.EMPTY,
                settings());
        return encode(input, ClusterTranslationReplay.Metadata.success(0L));
    }

    private static byte[] encode(
            ClusterTranslationInput input,
            ClusterTranslationReplay.Metadata metadata) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ClusterTranslationReplay.write(output, input, metadata);
        return output.toByteArray();
    }

    private static byte[] uncompressed(byte[] encoded) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
            return input.readAllBytes();
        }
    }

    private static byte[] compressed(byte[] raw) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(raw);
        }
        return output.toByteArray();
    }

    private static void assertMalformed(byte[] encoded) {
        assertThrows(
                IOException.class,
                () -> ClusterTranslationReplay.read(new ByteArrayInputStream(encoded)));
    }

    private static void assertMeshEquals(CpuClusterMesh expected, CpuClusterMesh actual) {
        assertEquals(expected.triangleCount(), actual.triangleCount());
        assertEquals(expected.segments().size(), actual.segments().size());
        for (int index = 0; index < expected.segments().size(); index++) {
            CpuClusterMesh.Segment left = expected.segments().get(index);
            CpuClusterMesh.Segment right = actual.segments().get(index);
            assertArrayEquals(left.positions(), right.positions());
            assertArrayEquals(left.primitiveRecords(), right.primitiveRecords());
            assertArrayEquals(left.surfaceRelationRecords(), right.surfaceRelationRecords());
        }
        assertEquals(expected.compatibilityIssues(), actual.compatibilityIssues());
    }

    private record ArrayPixels(int width, int height, int[] values)
            implements SpritePixelView {
        @Override
        public int imageWidth() {
            return this.width;
        }

        @Override
        public int imageHeight() {
            return this.height;
        }

        @Override
        public int argb(int x, int y) {
            return this.values[x + y * this.width];
        }
    }
}
