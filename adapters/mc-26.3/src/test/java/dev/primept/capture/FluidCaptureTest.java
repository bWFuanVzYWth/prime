package dev.primept.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidCaptureTest {
    @Test
    void nestedDefaultHandlerCapturesOnceAndRestoresUnlitTintWithoutChangingRaster() {
        var inbox = new CaptureInbox(true);
        int[] raster = new int[2];
        VertexConsumer original = sink(raster);
        try (var terrain = TerrainCapture.open(inbox, SectionPos.of(-2, 4, 7), true)) {
            try (var outer = FluidCapture.open(layer -> original)) {
                try (var inner = FluidCapture.open(outer)) {
                    VertexConsumer target = inner.getBuilder(ChunkSectionLayer.TRANSLUCENT);
                    FluidCapture.observedTint(0x80402010);
                    FluidCapture.vanillaVertex(true);
                    for (int i = 0; i < 4; i++)
                        target.addVertex(i, 2, 3, 0x80201008, .2f, .3f, 0, 42, 0, 1, 0);
                    FluidCapture.vanillaVertex(false);
                }
            }
            terrain.publish();
        }
        assertEquals(4, raster[0], "Nested Fabric wrapper must forward the original emission once");
        assertEquals(0x80201008, raster[1], "Vanilla receives its original shaded color");
        var wire = onlyMesh(inbox);
        assertEquals(4, wire.getInt(88));
        assertEquals(SourceQuads.TRANSLUCENT, wire.getInt(80));
        assertEquals(SourceQuads.TRANSLUCENT, wire.getInt(72));
        assertEquals(-32, wire.getDouble(40));
        assertEquals(64, wire.getDouble(48));
        assertEquals(112, wire.getDouble(56));
        for (int i = 0; i < 4; i++)
            assertEquals(0x40201080, Integer.reverseBytes(wire.getInt(112 + i * 24 + 12)));
    }

    @Test
    void customChainedVerticesRetainPerCornerSourceColorAndFlushTheLastVertex() {
        var inbox = new CaptureInbox(true);
        try (var terrain = TerrainCapture.open(inbox, SectionPos.of(0, 0, 0), true)) {
            try (var fluid = FluidCapture.open(layer -> sink(new int[2]))) {
                var target = fluid.getBuilder(ChunkSectionLayer.TRANSLUCENT);
                FluidCapture.observedTint(
                        0xff000000); // A custom producer's authored color is already its source result.
                for (int i = 0; i < 4; i++)
                    target.addVertex(i, 2, 3).setColor(10 + i, 20, 30, 40).setUv(.1f, .7f);
            }
            terrain.publish();
        }
        var wire = onlyMesh(inbox);
        assertEquals(4, wire.getInt(88));
        for (int i = 0; i < 4; i++) {
            assertEquals(i, wire.getFloat(112 + i * 24));
            assertEquals(10 + i, wire.get(112 + i * 24 + 12));
            assertEquals(40, wire.get(112 + i * 24 + 15));
        }
    }

    @Test
    void malformedCustomPrimitiveFailsItsSectionAndScopeDoesNotLeak() {
        var inbox = new CaptureInbox(true);
        try (var terrain = TerrainCapture.open(inbox, SectionPos.of(0, 0, 0), true)) {
            try (var fluid = FluidCapture.open(layer -> sink(new int[2]))) {
                fluid.getBuilder(ChunkSectionLayer.TRANSLUCENT)
                        .addVertex(0, 0, 0)
                        .setColor(-1)
                        .setUv(0, 0);
            }
            terrain.publish();
        }
        assertNotNull(inbox.failure());
        assertNull(inbox.poll());
        assertNull(FluidCapture.open(layer -> sink(new int[2])));
    }

    private static VertexConsumer sink(int[] raster) {
        return (VertexConsumer)Proxy.newProxyInstance(
                VertexConsumer.class.getClassLoader(), new Class<?>[] {VertexConsumer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addVertex")) {
                        ++raster[0];
                        if (args.length == 11)
                            raster[1] = (int)args[3];
                    }
                    return method.getReturnType() == void.class ? null : proxy;
                });
    }

    private static ByteBuffer onlyMesh(CaptureInbox inbox) {
        assertNull(inbox.failure());
        var batch = inbox.poll();
        assertNotNull(batch);
        assertEquals(1, batch.packets().size());
        var wire = ByteBuffer.wrap(batch.packets().getFirst()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(8, wire.getInt(8));
        assertEquals(1, wire.getInt(64));
        return wire;
    }
}
