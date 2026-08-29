package dev.prime.render.vulkan.nrd;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NrdNativeContractTest {
    @Test
    void nativePlatformDetectionRejectsUnsupportedSystemsAndArchitectures() {
        assertTrue(NrdNative.isSupportedPlatform("Windows 11", "amd64"));
        assertTrue(NrdNative.isSupportedPlatform("WINDOWS 10", "x86_64"));
        assertFalse(NrdNative.isSupportedPlatform("Linux", "amd64"));
        assertFalse(NrdNative.isSupportedPlatform("Darwin", "x86_64"));
        assertFalse(NrdNative.isSupportedPlatform("Windows 11", "aarch64"));
    }
}
