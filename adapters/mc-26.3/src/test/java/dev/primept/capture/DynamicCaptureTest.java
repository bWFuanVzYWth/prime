package dev.primept.capture;

import static dev.primept.abi.PrimeAbi.*;
import java.lang.foreign.MemorySegment;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicCaptureTest {
    @Test
    void actualEntityMeshRetainsSourceRgbaWhileLightAndNormalsStaySeparate() {
        try (var storage = new ByteBufferBuilder(256); var frame = new DynamicFrame(256)) {
            frame.begin(1, 1, 100, 70, -20);
            var builder =
                    new BufferBuilder(storage, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
            for (int i = 0; i < 4; ++i)
                builder.addVertex(i, i + 1, -i)
                        .setColor(0x804080C0)
                        .setUv(0.25f, 0.75f)
                        .setOverlay(0)
                        .setLight(i == 0 ? 0 : 0x00F000F0)
                        .setNormal(0, 1, 0);
            try (MeshData mesh = builder.buildOrThrow()) {
                assertTrue(DynamicCapture.appendMesh(frame, 2, 2, mesh));
                var span = onlySpan(frame.seal());
                var bytes = vertices(span).asByteBuffer();
                assertEquals(2, PrimeMeshSpan.texture_id(span));
                assertEquals(4, PrimeMeshSpan.vertex_count(span));
                int stride = PrimeMeshSpan.stride(span), color = PrimeMeshSpan.color_offset(span);
                for (int i = 0; i < 4; ++i) {
                    int offset = i * stride + color;
                    assertEquals(0x40, Byte.toUnsignedInt(bytes.get(offset)));
                    assertEquals(0x80, Byte.toUnsignedInt(bytes.get(offset + 1)));
                    assertEquals(0xC0, Byte.toUnsignedInt(bytes.get(offset + 2)));
                    assertEquals(0x80, Byte.toUnsignedInt(bytes.get(offset + 3)));
                }
            }
        }
    }

    @Test
    void missingTextureCoordinatesAreRejectedInsteadOfAssumingOffsets() {
        try (var storage = new ByteBufferBuilder(256); var frame = new DynamicFrame(256)) {
            frame.begin(1, 1, 0, 0, 0);
            var builder = new BufferBuilder(storage, PrimitiveTopology.TRIANGLES,
                                            DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i < 3; ++i)
                builder.addVertex(i, 0, 0).setColor(-1);
            try (MeshData mesh = builder.buildOrThrow()) {
                assertThrows(IllegalArgumentException.class,
                             () -> DynamicCapture.appendMesh(frame, 0, 0, mesh));
                assertEquals(0, frame.spanCount());
            }
        }
    }
    @Test
    void instancedRangesAreExcludedWithoutChangingOriginalMeshOrRemainingRawOrder() {
        try (var storage = new ByteBufferBuilder(2048); var frame = new DynamicFrame(256)) {
            frame.begin(1, 1, 0, 0, 0);
            var builder =
                    new BufferBuilder(storage, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
            for (int i = 0; i < 16; ++i)
                builder.addVertex(i, 0, 0)
                        .setColor(-1)
                        .setUv(0, 0)
                        .setOverlay(0)
                        .setLight(0)
                        .setNormal(0, 1, 0);
            try (var mesh = builder.buildOrThrow()) {
                var excluded = new DynamicCapture.ExcludedRanges();
                excluded.add(4, 8);
                excluded.add(8, 12);
                assertTrue(DynamicCapture.appendMesh(frame, 2, 1, mesh, excluded));
                assertEquals(
                        16, mesh.drawState().vertexCount()); // Original raster buffer is untouched.
                assertEquals(8, frame.vertexCount());
                var span = onlySpan(frame.seal());
                var packet = vertices(span).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
                int stride = PrimeMeshSpan.stride(span),
                    position = PrimeMeshSpan.position_offset(span);
                assertEquals(0, packet.getFloat(position));
                assertEquals(3, packet.getFloat(position + 3 * stride));
                assertEquals(12, packet.getFloat(position + 4 * stride));
            }
        }
    }
    @Test
    void mixedExclusionIntervalsFormOrderedUnionWithoutDuplicatingRawGaps() {
        try (var storage = new ByteBufferBuilder(2048); var frame = new DynamicFrame(256)) {
            frame.begin(1, 1, 0, 0, 0);
            var builder =
                    new BufferBuilder(storage, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
            for (int i = 0; i < 32; ++i)
                builder.addVertex(i, 0, 0)
                        .setColor(-1)
                        .setUv(0, 0)
                        .setOverlay(0)
                        .setLight(0)
                        .setNormal(0, 1, 0);
            try (var mesh = builder.buildOrThrow()) {
                var excluded = new DynamicCapture.ExcludedRanges();
                excluded.add(12, 16);
                excluded.add(4, 8);
                excluded.add(8, 12);
                excluded.add(8, 16);
                excluded.add(20, 24);
                excluded.add(24, 28);
                assertThrows(IllegalArgumentException.class, () -> excluded.add(-4, 4));
                assertTrue(DynamicCapture.appendMesh(frame, 0, 0, mesh, excluded));
                assertEquals(12, frame.vertexCount());
                var span = onlySpan(frame.seal());
                var bytes = vertices(span).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
                int stride = PrimeMeshSpan.stride(span),
                    position = PrimeMeshSpan.position_offset(span);
                assertEquals(0, bytes.getFloat(position));
                assertEquals(3, bytes.getFloat(3 * stride + position));
                assertEquals(16, bytes.getFloat(4 * stride + position));
                assertEquals(28, bytes.getFloat(8 * stride + position));
                assertEquals(31, bytes.getFloat(11 * stride + position));
            }
        }
    }
    private static MemorySegment onlySpan(MemorySegment batch) {
        assertEquals(1, PrimeDynamicBatch.count(batch));
        return PrimeDynamicBatch.spans(batch).reinterpret(PrimeMeshSpan.SIZE);
    }
    private static MemorySegment vertices(MemorySegment span) {
        var bytes = PrimeMeshSpan.vertices(span);
        return PrimeByteSpan.data(bytes).reinterpret(PrimeByteSpan.count(bytes));
    }
}
