// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/support/conversion.slang";

import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";

public bool3 lessThan(float3 first, float3 second) { return first < second; }
public bool3 lessThanEqual(float3 first, float3 second) { return first <= second; }

// Minecraft's translucent render layer exposes fixed conditional reflection/transmission proposals
// for the first-visible split, then uses the complete compact closure for later queued
// continuation. The adapter owns the conditional primary proposals and medium handoff.
public struct PrimeTransmissiveBsdfSample {
    public BsdfSample bsdfSample;
    public PrimeOpenPbrVolume medium;
};

public struct PrimeTransmissiveBsdfSplit {
    public PrimeTransmissiveBsdfSample reflection;
    public PrimeTransmissiveBsdfSample transmission;
};

public bool primeOpenPbrHasSample(
        PrimeOpenPbrSample sampleValue) {
    if (sampleValue.throughput.flags == PRIME_OPENPBR_FLAG_NONE) {
        // A rejected proposal has no consumable payload. Prime replaces it with its canonical
        // zero-event sample, so inspecting discarded fields only creates diagnostic false positives.
        return false;
    }
    // relativeEta is sanitized after the adapter has accepted the internal proposal. Observe the
    // raw value here so that the sanitizer cannot erase the first numerical failure.
    if (!primeBsdfDirection(sampleValue.wo)
            || !primeBsdfFinite(sampleValue.throughput.value)
            || !primeBsdfFinite(sampleValue.pdf)
            || any(lessThan(sampleValue.throughput.value, float3(0.0)))
            || !(sampleValue.pdf > 0.0)) {
        // Float singularities in the reference closure are contained at Prime's adapter boundary.
        // A rejected proposal contributes zero and cannot poison later MIS or throughput state.
        return false;
    }
    return true;
}

public uint primeOpenPbrToBsdfEventFlags(uint flags) {
    uint result = 0u;
    if (primeOpenPbrIsReflective(flags)) {
        result |= PRIME_BSDF_EVENT_REFLECTION;
    }
    if (primeOpenPbrIsTransmissive(flags)) {
        result |= PRIME_BSDF_EVENT_TRANSMISSION;
    }
    if (primeOpenPbrIsDiffuse(flags)) {
        result |= PRIME_BSDF_EVENT_DIFFUSE;
    }
    if (primeOpenPbrIsSpecular(flags)) {
        result |= PRIME_BSDF_EVENT_GLOSSY;
    }
    if (primeOpenPbrIsDelta(flags)) {
        result |= PRIME_BSDF_EVENT_DISCRETE;
    }
    return result;
}
