package dev.prime.render.vulkan.fsr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("native")
final class FsrNativeExecutionTest {
    @Test
    void bundledLibraryLoadsAndExportsTheFsrApi() {
        assertDoesNotThrow(FsrNative::verifyLibrary);
    }
}
