package dev.primept.capture;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Runs against each real version's MC/Fabric types and integer color implementation. */
class TerrainCaptureTest {
    private static final SectionPos SECTION = SectionPos.of(-7, 3, 9);
    private static final BlockPos POSITION = new BlockPos(-112, 48, 144);

    @Test void fabricPreservesAuthoredCornersAndUsesObservedTintWithoutRasterShade() throws Exception {
        int[] authored = {0xffabcdef, 0x7f123456, 0x80402010, 0xff020304};
        int[] mutable = authored.clone();
        int tint = 0x9b7ea3d9;
        var quad = quad(mutable, 4);
        var inbox = new CaptureInbox(true);
        try (var scope = TerrainCapture.open(inbox, SECTION, true)) {
            TerrainCapture.beginFabricQuad(quad);
            // Simulate Indigo mutating the same quad in place, including 8-bit rounding.
            for (int i = 0; i < 4; i++) mutable[i] = ARGB.multiply(ARGB.scaleRGB(mutable[i], .2f), tint);
            TerrainCapture.fabricTint(tint);
            TerrainCapture.finishFabricQuad(quad, true);
            scope.publish();
        }
        byte[] packet = onlyMesh(inbox);
        int[] expected = {0x9b5483cb, 0x4d082149, 0x4d1f140d, 0x9b000103};
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i], ARGB.multiply(authored[i], tint));
            assertEquals(expected[i], color(packet, i));
            assertNotEquals(mutable[i], color(packet, i));
        }
        var bytes = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(24, bytes.getInt(68));
        assertEquals(1, bytes.getInt(92)); // Stable wire flag and layer, not an MC ordinal.
        assertEquals(SourceQuads.CUTOUT, bytes.getInt(96));
        assertEquals(-112d, bytes.getDouble(40));
        assertEquals(48d, bytes.getDouble(48));
        assertEquals(144d, bytes.getDouble(56));
        assertEquals(10.25f, bytes.getFloat(104)); // Accepted, already translated local position.
        System.out.println("Prime PT pre-light source fixture SHA256="
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(packet)));
    }

    @Test void rejectedFabricQuadDoesNotRequireTintOrEmitGeometry() {
        var inbox = new CaptureInbox(true);
        try (var scope = TerrainCapture.open(inbox, SECTION, true)) {
            var rejected = quad(new int[] {-1, -1, -1, -1}, 2);
            TerrainCapture.beginFabricQuad(rejected);
            TerrainCapture.finishFabricQuad(rejected, false);
            var accepted = quad(new int[] {0x7fabcdef, -1, -1, -1}, -1);
            TerrainCapture.beginFabricQuad(accepted);
            TerrainCapture.finishFabricQuad(accepted, true);
            scope.publish();
        }
        assertEquals(0x7fabcdef, color(onlyMesh(inbox), 0));
    }

    @Test void missingObservedTintFailsInsteadOfGuessingFinalColor() {
        var inbox = new CaptureInbox(true);
        try (var scope = TerrainCapture.open(inbox, SECTION, true)) {
            var quad = quad(new int[] {-1, -1, -1, -1}, 0);
            TerrainCapture.beginFabricQuad(quad);
            TerrainCapture.finishFabricQuad(quad, true);
            scope.publish();
        }
        assertNotNull(inbox.failure());
        assertNull(inbox.poll());
    }

    @Test void vanillaUsesSourceTintAndOffsetWithoutQuadInstanceLightingForEveryDirection() {
        var inbox = new CaptureInbox(true);
        int tint = 0x7f8ab3d9;
        try (var scope = TerrainCapture.open(inbox, SECTION, true)) {
            for (Direction direction : Direction.values()) {
                var quad = baked(direction, 3);
                TerrainCapture.beginVanilla(POSITION, 3);
                TerrainCapture.vanillaTint(POSITION, 3, tint);
                TerrainCapture.vanillaQuad(2.5f, 3, 4, null, quad);
            }
            scope.publish();
        }
        var packet = onlyMesh(inbox);
        var bytes = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(6 * 4, bytes.getInt(64));
        for (int i = 0; i < 6 * 4; i++) assertEquals(tint, color(packet, i));
        assertEquals(2.5f, bytes.getFloat(104));
    }

    @Test void abortedScopePublishesNothingAndCannotLeakIntoTheNextCompile() {
        var inbox = new CaptureInbox(true);
        assertThrows(IllegalArgumentException.class, () -> {
            try (var ignored = TerrainCapture.open(inbox, SECTION, true)) {
                TerrainCapture.beginFabricQuad(quad(new int[] {-1, -1, -1, -1}, 0));
                throw new IllegalArgumentException("source model callback failed");
            }
        });
        // No active capture: a callback outside compile must neither publish nor complete the old quad.
        TerrainCapture.fabricTint(-1);
        assertNull(inbox.poll());
        try (var scope = TerrainCapture.open(inbox, SECTION, true)) {
            TerrainCapture.beginVanilla(POSITION, -1);
            TerrainCapture.vanillaQuad(0, 0, 0, null, baked(Direction.UP, -1));
            scope.publish();
        }
        assertEquals(-1, color(onlyMesh(inbox), 0));
        assertNull(inbox.failure());
    }

    @Test void resetDuringScopeRejectsItsFixedEpochAndPendingGeometry() {
        var inbox = new CaptureInbox(true);
        try (var scope = TerrainCapture.open(inbox, SECTION, true)) {
            TerrainCapture.beginVanilla(POSITION, -1);
            TerrainCapture.vanillaQuad(0, 0, 0, null, baked(Direction.UP, -1));
            inbox.reset();
            scope.publish();
        }
        assertNull(inbox.poll());
        assertNull(inbox.failure());
    }

    @Test void fastLeavesKeepIndigosActualCutoutLayerWhileVanillaUsesItsSolidOutput() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var leaves = Blocks.OAK_LEAVES.defaultBlockState();
        assertTrue(ModelBlockRenderer.forceOpaque(false, leaves));
        assertFalse(ModelBlockRenderer.forceOpaque(true, leaves));
        var inbox = new CaptureInbox(true);
        try (var scope = TerrainCapture.open(inbox, SECTION, false)) {
            TerrainCapture.beginFabricBlock(leaves);
            var quad = quad(new int[] {-1, -1, -1, -1}, -1);
            TerrainCapture.beginFabricQuad(quad);
            TerrainCapture.finishFabricQuad(quad, true);
            TerrainCapture.beginVanilla(POSITION, -1);
            TerrainCapture.vanillaQuad(0, 0, 0, leaves, baked(Direction.UP, -1, ChunkSectionLayer.CUTOUT));
            scope.publish();
        }
        assertNull(inbox.failure());
        var batch = inbox.poll();
        assertEquals(3, batch.packets().size()); // Removal, vanilla SOLID, then Indigo CUTOUT.
        var opaque = ByteBuffer.wrap(batch.packets().get(1)).order(ByteOrder.LITTLE_ENDIAN);
        var cutout = ByteBuffer.wrap(batch.packets().get(2)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(4, opaque.getInt(64));
        assertEquals(0, opaque.getInt(92));
        assertEquals(SourceQuads.OPAQUE, opaque.getInt(96));
        assertEquals(4, cutout.getInt(64));
        assertEquals(1, cutout.getInt(92));
        assertEquals(SourceQuads.CUTOUT, cutout.getInt(96));
    }

    @Test void translucentVanillaAndFabricQuadsKeepSourceAlphaAndShareOneSectionLayer() {
        var inbox = new CaptureInbox(true);
        try (var scope = TerrainCapture.open(inbox, SECTION, true)) {
            TerrainCapture.beginVanilla(POSITION, 2);
            TerrainCapture.vanillaTint(POSITION, 2, 0x80776655);
            TerrainCapture.vanillaQuad(0, 0, 0, null, baked(Direction.UP, 2, ChunkSectionLayer.TRANSLUCENT));
            var quad = quad(new int[] {0x40402010, -1, -1, -1}, -1, ChunkSectionLayer.TRANSLUCENT);
            TerrainCapture.beginFabricQuad(quad);
            TerrainCapture.finishFabricQuad(quad, true);
            scope.publish();
        }
        byte[] packet = onlyMesh(inbox);
        var wire = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(8, wire.getInt(64));
        assertEquals(2, wire.getInt(92));
        assertEquals(SourceQuads.TRANSLUCENT, wire.getInt(96));
        assertEquals(0x80776655, color(packet, 0));
        assertEquals(0x40402010, color(packet, 4));
    }

    private static BakedQuad baked(Direction direction, int tintIndex) {
        return baked(direction, tintIndex, ChunkSectionLayer.SOLID);
    }

    private static BakedQuad baked(Direction direction, int tintIndex, ChunkSectionLayer layer) {
        var material = new BakedQuad.MaterialInfo(null, layer, null, tintIndex, true, 0);
        return new BakedQuad(new Vector3f(0, 0, 0), new Vector3f(1, 0, 0), new Vector3f(1, 1, 0),
                new Vector3f(0, 1, 0), UVPair.pack(.1f, .2f), UVPair.pack(.3f, .2f),
                UVPair.pack(.3f, .4f), UVPair.pack(.1f, .4f), direction, material);
    }

    private static MutableQuadView quad(int[] colors, int tintIndex) {
        return quad(colors, tintIndex, ChunkSectionLayer.CUTOUT);
    }

    private static MutableQuadView quad(int[] colors, int tintIndex, ChunkSectionLayer layer) {
        return (MutableQuadView) Proxy.newProxyInstance(MutableQuadView.class.getClassLoader(),
                new Class<?>[] {MutableQuadView.class}, (_, method, args) -> switch (method.getName()) {
                    case "color" -> colors[(int) args[0]];
                    case "tintIndex" -> tintIndex;
                    case "atlas" -> QuadAtlas.BLOCK;
                    case "chunkLayer" -> layer;
                    case "x" -> 10.25f + (int) args[0];
                    case "y" -> 2f;
                    case "z" -> 3f;
                    case "u" -> .1f + (int) args[0] * .01f;
                    case "v" -> .2f;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static byte[] onlyMesh(CaptureInbox inbox) {
        assertNull(inbox.failure());
        var batch = inbox.poll();
        assertNotNull(batch);
        assertEquals(2, batch.packets().size());
        assertNull(inbox.poll());
        return batch.packets().get(1);
    }

    private static int color(byte[] packet, int vertex) {
        int p = 104 + vertex * SourceQuads.STRIDE + 12;
        return (packet[p + 3] & 255) << 24 | (packet[p] & 255) << 16
                | (packet[p + 1] & 255) << 8 | packet[p + 2] & 255;
    }
}
