package dev.prime.render.vulkan.nrd;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class NrdMotionBindingContractTest {
    @Test
    void nrdAndFsrMotionUseDistinctShaderOutputs() {
        Object nrdMotion = new Object();
        Object fsrMotion = new Object();
        Object reconstructionControl = new Object();
        Object[] bindings = new Object[NrdDenoiser.MOTION_BINDING_COUNT];
        bindings[NrdDenoiser.MOTION_NRD_BINDING] = nrdMotion;
        bindings[NrdDenoiser.MOTION_FSR_BINDING] = fsrMotion;
        bindings[NrdDenoiser.MOTION_CONTROL_BINDING] = reconstructionControl;

        assertDoesNotThrow(() -> NrdDenoiser.validateMotionBindings(
                bindings, nrdMotion, fsrMotion, reconstructionControl));

        bindings[NrdDenoiser.MOTION_NRD_BINDING] = fsrMotion;
        assertThrows(
                IllegalStateException.class,
                () -> NrdDenoiser.validateMotionBindings(
                        bindings, nrdMotion, fsrMotion, reconstructionControl));
    }
}
