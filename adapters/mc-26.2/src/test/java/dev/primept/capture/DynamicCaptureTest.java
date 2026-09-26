package dev.primept.capture;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicCaptureTest {
    @Test void actualEntityMeshRetainsSourceRgbaWhileLightAndNormalsStaySeparate() {
        try (var storage = new ByteBufferBuilder(256); var frame = new DynamicFrame(256)) {
            frame.begin(1, 1, 100, 70, -20);
            var builder = new BufferBuilder(storage, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
            for (int i = 0; i < 4; ++i)
                builder.addVertex(i, i + 1, -i).setColor(0x804080C0).setUv(0.25f, 0.75f)
                        .setOverlay(0).setLight(i == 0 ? 0 : 0x00F000F0).setNormal(0, 1, 0);
            try (MeshData mesh = builder.buildOrThrow()) {
                assertTrue(DynamicCapture.appendMesh(frame, 2, 2, mesh));
                var bytes = frame.seal().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(1, bytes.getInt(56));
                assertEquals(2, bytes.getInt(64));
                assertEquals(4, bytes.getInt(76));
                int stride = bytes.getInt(80), color = bytes.getInt(88);
                for (int i = 0; i < 4; ++i) {
                    int offset = 96 + i * stride + color;
                    assertEquals(0x40, Byte.toUnsignedInt(bytes.get(offset)));
                    assertEquals(0x80, Byte.toUnsignedInt(bytes.get(offset + 1)));
                    assertEquals(0xC0, Byte.toUnsignedInt(bytes.get(offset + 2)));
                    assertEquals(0x80, Byte.toUnsignedInt(bytes.get(offset + 3)));
                }
            }
        }
    }

    @Test void missingTextureCoordinatesAreRejectedInsteadOfAssumingOffsets() {
        try (var storage = new ByteBufferBuilder(256); var frame = new DynamicFrame(256)) {
            frame.begin(1, 1, 0, 0, 0);
            var builder = new BufferBuilder(storage, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i < 3; ++i) builder.addVertex(i, 0, 0).setColor(-1);
            try (MeshData mesh = builder.buildOrThrow()) {
                assertThrows(IllegalArgumentException.class, () -> DynamicCapture.appendMesh(frame, 0, 0, mesh));
                assertEquals(0, frame.spanCount());
            }
        }
    }
    @Test void instancedRangesAreExcludedWithoutChangingOriginalMeshOrRemainingRawOrder() {
        try (var storage = new ByteBufferBuilder(2048); var frame = new DynamicFrame(256)) {
            frame.begin(1, 1, 0, 0, 0);
            var builder = new BufferBuilder(storage, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
            for (int i = 0; i < 16; ++i)
                builder.addVertex(i, 0, 0).setColor(-1).setUv(0, 0).setOverlay(0).setLight(0).setNormal(0, 1, 0);
            try (var mesh = builder.buildOrThrow()) {
                var excluded = new DynamicCapture.ExcludedRanges();
                excluded.add(4, 8); excluded.add(8, 12);
                assertTrue(DynamicCapture.appendMesh(frame, 2, 1, mesh, excluded));
                assertEquals(16, mesh.drawState().vertexCount()); // Original raster buffer is untouched.
                assertEquals(8, frame.vertexCount());
                var packet = frame.seal().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
                int stride = packet.getInt(80);
                assertEquals(0, packet.getFloat(96));
                assertEquals(3, packet.getFloat(96 + 3 * stride));
                assertEquals(12, packet.getFloat(96 + 4 * stride));
            }
        }
    }
}
