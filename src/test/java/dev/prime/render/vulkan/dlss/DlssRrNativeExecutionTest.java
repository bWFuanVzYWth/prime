package dev.prime.render.vulkan.dlss;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("native")
final class DlssRrNativeExecutionTest {
    @Test
    void bundledBridgeExportsTheDeclaredAbi() {
        assertDoesNotThrow(DlssRrNative::verifyLibrary);
    }
}
