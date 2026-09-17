// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/realtime_control.slang";

import "contract/realtime/path.slang";

public bool primeWavefrontTransparentBranch(uint control) {
    return (control & PRIME_WAVEFRONT_TRANSPARENT_BRANCH) != 0u;
}

public bool primeWavefrontTransmissionBranch(uint control) {
    return (control & PRIME_WAVEFRONT_TRANSMISSION_BRANCH) != 0u;
}

public bool primeWavefrontGuideEnabled(uint control) {
    return (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
}

public bool primeWavefrontDirectionalGuide(uint control) {
    return (control & PRIME_WAVEFRONT_DIRECTIONAL_GUIDE) != 0u;
}

public bool primeWavefrontGuideFallback(uint guideControl) {
    return (guideControl & PRIME_WAVEFRONT_GUIDE_FALLBACK) != 0u;
}

public bool primeWavefrontGuideDetached(uint guideControl) {
    return (guideControl & PRIME_WAVEFRONT_GUIDE_DETACHED) != 0u;
}

// Entering secondary resets local sampling/guide counters, never physical event history or IOR.
public PrimeWavefrontContinuationControl primeEnterSecondaryControl(
        PrimeWavefrontContinuationControl control) {
    control.path &= ~PRIME_WAVEFRONT_BYTE_MASK;
    control.denoiser &= ~(PRIME_WAVEFRONT_BYTE_MASK
            << PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT);
    control.guide &= ~(PRIME_WAVEFRONT_PSR_CONTROL_MASK
            | PRIME_WAVEFRONT_GUIDE_FALLBACK);
    return control;
}
