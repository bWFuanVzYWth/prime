// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "policy/transport/path.slang";

// The local secondary counter resets after the primary non-delta scatter. Adding one reconstructs
// the primary-inclusive scatter index used by realtime roulette.
public uint primeRealtimeRouletteScatterIndex(uint secondaryBounce) {
    return secondaryBounce + 1u;
}

// Stored secondary state excludes the completed primary scatter from its local counter.
public uint primeRealtimeCompletedBounceCount(uint secondaryBounce) {
    return secondaryBounce + 1u;
}

// A shared transparent observation blocks roulette until it has a stable solid-angle guide.
public bool primeTransparentTransportOwnsGuide(
        bool permanentOwner,
        bool independentProbeFallback) {
    return permanentOwner || !independentProbeFallback;
}

public bool primeTransparentPrimaryReflectionOnly(
        bool discreteOnly,
        bool hasTransmission,
        bool hasReflection) {
    return discreteOnly && !hasTransmission && hasReflection;
}

public bool primeTransparentRouletteBlocked(
        bool pathOwnsGuide,
        bool guideEnabled,
        bool hasGuide) {
    return pathOwnsGuide && guideEnabled && !hasGuide;
}
