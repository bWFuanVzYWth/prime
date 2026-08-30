package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic correctness corpus shared by translation tests and JMH, never production code. */
public final class ClusterTranslationBenchmarkCorpus {
    private static final CapturedSprite BASE = sprite("benchmark/base", 1);
    private static final CapturedSprite OVERLAY = sprite("benchmark/overlay", 2);
    private static final CapturedSprite MEDIUM_A = sprite("benchmark/medium_a", 3);
    private static final CapturedSprite MEDIUM_B = sprite("benchmark/medium_b", 4);

    private ClusterTranslationBenchmarkCorpus() {}

    public static ClusterTranslationInput input(String id) {
        return switch (id) {
            case "typical" -> new ClusterTranslationInput(
                    typicalCluster(), LabPbrMaterialSet.EMPTY, settings());
            case "extreme" -> new ClusterTranslationInput(
                    extremeCluster(), LabPbrMaterialSet.EMPTY, settings());
            default -> throw new IllegalArgumentException("Unknown translation corpus: " + id);
        };
    }

    public static Fingerprint expected(String id) {
        return switch (id) {
            case "typical" -> new Fingerprint(
                    "3882d4f51e05353426c62cdc6c06ef55c430063523f8ab024b6ded37f467cd97",
                    134L,
                    70L,
                    7456L);
            case "extreme" -> new Fingerprint(
                    "9beccf5cc7ecd1bd147836c4fd6466d7e86b15ff0c722024b2e1b51d6ff96e99",
                    2174L,
                    2111L,
                    195220L);
            default -> throw new IllegalArgumentException("Unknown translation corpus: " + id);
        };
    }

    public static Fingerprint translateAndFingerprint(String id) {
        return fingerprint(ClusterSceneTranslator.translate(input(id)));
    }

    public static Fingerprint fingerprint(CpuClusterMesh mesh) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, mesh.opaqueTriangleCount());
            update(digest, mesh.cutoutTriangleCount());
            update(digest, mesh.transmissiveTriangleCount());
            update(digest, mesh.opaqueMacroTriangleCount());
            update(digest, mesh.cutoutMacroTriangleCount());
            update(digest, mesh.transmissiveMacroTriangleCount());
            update(digest, mesh.segments().size());
            for (CpuClusterMesh.Segment segment : mesh.segments()) {
                update(digest, segment.opaqueTriangleCount());
                update(digest, segment.cutoutTriangleCount());
                update(digest, segment.transmissiveTriangleCount());
                for (float value : segment.positions()) {
                    update(digest, Float.floatToRawIntBits(value));
                }
                for (int value : segment.primitiveRecords()) {
                    update(digest, value);
                }
                for (int value : segment.surfaceRelationRecords()) {
                    update(digest, value);
                }
            }
            return new Fingerprint(
                    HexFormat.of().formatHex(digest.digest()),
                    mesh.triangleCount(),
                    mesh.primitiveCount(),
                    mesh.byteSize());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by Java", exception);
        }
    }

    public static void main(String[] arguments) {
        for (String id : new String[] {"typical", "extreme"}) {
            System.out.println(id + "=" + translateAndFingerprint(id));
        }
    }

    private static CapturedCluster typicalCluster() {
        CapturedCluster.Builder cluster = new CapturedCluster.Builder(0, 0, 0);
        CapturedSectionGeometry.Surface ordinary = surface(
                BASE, CapturedSectionGeometry.Layer.OPAQUE, false, null);
        for (int z = 0; z < SectionCluster.SECTION_SIZE; z++) {
            for (int y = 0; y < SectionCluster.SECTION_SIZE; y++) {
                for (int x = 0; x < SectionCluster.SECTION_SIZE; x++) {
                    CapturedSectionGeometry.Builder section =
                            new CapturedSectionGeometry.Builder();
                    section.add(rectangleXFace(
                            2.0F, 1.0F, 2.0F, 3.0F, 2.0F, 3.0F), ordinary);
                    if (x == 0 && y == 0 && z == 0) {
                        section.add(
                                unitXFace(4.0F, 1.0F),
                                surface(BASE, CapturedSectionGeometry.Layer.OPAQUE, false, null));
                        section.add(
                                unitXFace(4.0F, 1.0F),
                                surface(OVERLAY, CapturedSectionGeometry.Layer.CUTOUT, true, null));
                        section.add(
                                unitXFace(16.0F, 1.0F),
                                surface(
                                        MEDIUM_A,
                                        CapturedSectionGeometry.Layer.TRANSLUCENT,
                                        false,
                                        new CapturedSectionGeometry.BlockFacts(15, 0, 0, 1)));
                        section.addPeer(
                                unitXFace(0.0F, -1.0F),
                                surface(
                                        MEDIUM_A,
                                        CapturedSectionGeometry.Layer.TRANSLUCENT,
                                        false,
                                        new CapturedSectionGeometry.BlockFacts(0, 0, 0, 1)));
                    }
                    if (x == 1 && y == 0 && z == 0) {
                        section.add(
                                unitXFace(0.0F, -1.0F),
                                surface(
                                        MEDIUM_B,
                                        CapturedSectionGeometry.Layer.TRANSLUCENT,
                                        false,
                                        new CapturedSectionGeometry.BlockFacts(16, 0, 0, 2)));
                    }
                    if (x == 3 && y == 3 && z == 3) {
                        section.add(fluidTop(), fluidSurface());
                    }
                    cluster.add(x, y, z, section.build());
                }
            }
        }
        return cluster.build();
    }

    private static CapturedCluster extremeCluster() {
        CapturedCluster.Builder cluster = new CapturedCluster.Builder(0, 0, 0);
        CapturedSectionGeometry.Surface ordinary = surface(
                BASE, CapturedSectionGeometry.Layer.OPAQUE, false, null);
        CapturedSectionGeometry.Surface positive = surface(
                MEDIUM_A,
                CapturedSectionGeometry.Layer.TRANSLUCENT,
                false,
                new CapturedSectionGeometry.BlockFacts(-1, 0, 0, 1));
        CapturedSectionGeometry.Surface negative = surface(
                MEDIUM_B,
                CapturedSectionGeometry.Layer.TRANSLUCENT,
                false,
                new CapturedSectionGeometry.BlockFacts(0, 0, 0, 2));
        for (int z = 0; z < SectionCluster.SECTION_SIZE; z++) {
            for (int y = 0; y < SectionCluster.SECTION_SIZE; y++) {
                for (int x = 0; x < SectionCluster.SECTION_SIZE; x++) {
                    CapturedSectionGeometry.Builder section =
                            new CapturedSectionGeometry.Builder();
                    if (x == 0 && y == 0 && z == 0) {
                        for (int strip = 0; strip < 32; strip++) {
                            float minimum = strip / 32.0F;
                            float maximum = (strip + 1) / 32.0F;
                            section.add(rectangleXFace(
                                    0.0F, 1.0F, minimum, maximum, 0.0F, 1.0F), positive);
                            section.add(rectangleXFace(
                                    0.0F, -1.0F, 0.0F, 1.0F, minimum, maximum), negative);
                        }
                    } else {
                        section.add(rectangleXFace(
                                2.0F, 1.0F, 2.0F, 3.0F, 2.0F, 3.0F), ordinary);
                    }
                    cluster.add(x, y, z, section.build());
                }
            }
        }
        return cluster.build();
    }

    private static CapturedSectionGeometry.MutableQuad fluidTop() {
        CapturedSectionGeometry.MutableQuad quad = new CapturedSectionGeometry.MutableQuad();
        float[] x = {0.0F, 0.0F, 1.0F, 1.0F};
        float[] z = {0.0F, 1.0F, 1.0F, 0.0F};
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = x[vertex];
            quad.y[vertex] = 1.0F;
            quad.z[vertex] = z[vertex];
            quad.u[vertex] = x[vertex];
            quad.v[vertex] = z[vertex];
        }
        quad.normalY = 1.0F;
        return quad;
    }

    private static CapturedSectionGeometry.Surface fluidSurface() {
        return new CapturedSectionGeometry.Surface(
                0xff40_80c0,
                0xff40_80c0,
                0xff40_80c0,
                0xff40_80c0,
                CapturedSectionGeometry.Layer.TRANSLUCENT,
                false,
                false,
                false,
                true,
                false,
                true,
                false,
                0,
                MEDIUM_A,
                new CapturedSectionGeometry.FluidFacts(0, 0, 0, false, 0),
                null);
    }

    private static CapturedSectionGeometry.Surface surface(
            CapturedSprite sprite,
            CapturedSectionGeometry.Layer layer,
            boolean rasterOverlay,
            CapturedSectionGeometry.BlockFacts block) {
        return CapturedSectionGeometry.Surface.uniform(
                0xff80_a0c0,
                layer,
                false,
                false,
                false,
                false,
                false,
                true,
                rasterOverlay,
                0,
                sprite,
                block);
    }

    private static CapturedSectionGeometry.MutableQuad unitXFace(float plane, float normal) {
        return rectangleXFace(plane, normal, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    private static CapturedSectionGeometry.MutableQuad rectangleXFace(
            float plane,
            float normal,
            float minimumY,
            float maximumY,
            float minimumZ,
            float maximumZ) {
        float[] y = normal > 0.0F
                ? new float[] {minimumY, maximumY, maximumY, minimumY}
                : new float[] {minimumY, minimumY, maximumY, maximumY};
        float[] z = normal > 0.0F
                ? new float[] {minimumZ, minimumZ, maximumZ, maximumZ}
                : new float[] {minimumZ, maximumZ, maximumZ, minimumZ};
        CapturedSectionGeometry.MutableQuad quad = new CapturedSectionGeometry.MutableQuad();
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = plane;
            quad.y[vertex] = y[vertex];
            quad.z[vertex] = z[vertex];
            quad.u[vertex] = (y[vertex] - minimumY) / (maximumY - minimumY);
            quad.v[vertex] = (z[vertex] - minimumZ) / (maximumZ - minimumZ);
        }
        quad.normalX = normal;
        return quad;
    }

    private static ClusterTranslationSettings settings() {
        return new ClusterTranslationSettings(
                false, 512, 2, 2, false, 0.0F, false, false);
    }

    private static CapturedSprite sprite(String path, int textureId) {
        return new CapturedSprite(
                new SpriteId("prime", path),
                textureId,
                16,
                16,
                false,
                new int[] {0},
                null);
    }

    private static void update(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    private static void update(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
    }

    public record Fingerprint(String sha256, long triangles, long primitives, long bytes) {}
}
