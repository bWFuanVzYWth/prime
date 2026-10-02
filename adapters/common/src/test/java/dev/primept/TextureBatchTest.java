package dev.primept;

import java.lang.foreign.Arena;
import java.util.Collections;
import java.util.List;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static dev.primept.abi.PrimeAbi.*;
import dev.primept.capture.Packets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextureBatchTest {
    @Test
    void oneRootPublishesAllDescriptorsAndTheirExactPayloads() {
        byte[] first = {1, 2, 3, 4}, second = {5, 6, 7, 8, 9, 10, 11, 12};
        var sources = List.of(new NativeBridge.TextureSource(2, 1, 1, first),
                              new NativeBridge.TextureSource(7, 2, 1, second));
        int size = NativeBridge.textureBatchBytes(sources);
        assertEquals(PrimeTextureBatch.SIZE + 2 * PrimeTextureSource.SIZE + 12, size);
        try (var arena = Arena.ofConfined()) {
            var storage = arena.allocate(size, 8);
            var root = NativeBridge.encodeTextures(storage, 42, sources);
            assertEquals(42, PrimeTextureBatch.epoch(root));
            assertEquals(2, PrimeTextureBatch.count(root));
            var descriptors =
                    PrimeTextureBatch.textures(root).reinterpret(2 * PrimeTextureSource.SIZE);
            for (int i = 0; i < 2; ++i) {
                var source =
                        descriptors.asSlice(i * PrimeTextureSource.SIZE, PrimeTextureSource.SIZE);
                var pixels = PrimeTextureSource.rgba(source);
                var expected = sources.get(i);
                assertEquals(expected.id(), PrimeTextureSource.id(source));
                assertEquals(expected.width(), PrimeTextureSource.width(source));
                assertEquals(1, PrimeTextureSource.height(source));
                assertEquals(0, PrimeTextureSource.reserved(source));
                assertEquals(expected.rgba().length, PrimeByteSpan.count(pixels));
                assertArrayEquals(expected.rgba(), PrimeByteSpan.data(pixels)
                                                           .reinterpret(expected.rgba().length)
                                                           .toArray(JAVA_BYTE));
            }
            assertEquals(storage.address() + size - second.length,
                         PrimeByteSpan
                                 .data(PrimeTextureSource.rgba(
                                         descriptors.asSlice(PrimeTextureSource.SIZE)))
                                 .address());
        }
    }

    @Test
    void budgetIncludesEveryDescriptorBeforeNativeStorageAllocation() {
        var source = new NativeBridge.TextureSource(2, 1, 1, new byte[4]);
        int count = (int)((Packets.MAX_PACKET_BYTES - PrimeTextureBatch.SIZE) /
                          (PrimeTextureSource.SIZE + 4));
        assertEquals(PrimeTextureBatch.SIZE + count * (PrimeTextureSource.SIZE + 4),
                     NativeBridge.textureBatchBytes(Collections.nCopies(count, source)));
        assertThrows(IllegalArgumentException.class,
                     () -> NativeBridge.textureBatchBytes(Collections.nCopies(count + 1, source)));
        assertThrows(IllegalArgumentException.class,
                     ()
                             -> NativeBridge.textureBatchBytes(List.of(
                                     new NativeBridge.TextureSource(2, 1, 1, new byte[3]))));
    }
}
