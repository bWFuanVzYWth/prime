// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/realtime_storage.slang";

import "prime_realtime_abi.slang";

// Queue indices retain branch identity; only the complete transport allocation folds to N slots.
public uint primeRealtimeTransportIndex(uint pathIndex, uint pixels) {
    // Slang emitted two nested selections per field load for the equivalent ternary form.
    // Logical indices are in [0, 2N); the product is exactly 0 or N, with no narrowing or modulo.
    return pathIndex - uint(pathIndex >= pixels) * pixels;
}

public uint primeRealtimeSurfaceWord(uint pathIndex, uint pixels) {
    return pixels * (PRIME_WAVEFRONT_AREA_GUIDE_RECORD_SIZE / 4u)
            + pathIndex * (PRIME_WAVEFRONT_SURFACE_RECORD_SIZE / 4u);
}

// An omitted branch owns 40 bytes of ray plus 28 bytes of exact guide control/PSR in its unused
// surface slot. This owner survives through resolve; it never aliases the selected transport.
public uint primeMissingGuideOwnerWord(uint pathIndex, uint pixels) {
    return primeRealtimeSurfaceWord(pathIndex, pixels) + PRIME_WAVEFRONT_DETACHED_GUIDE_RECORD_SIZE / 4u;
}

public static const uint PRIME_MISSING_GUIDE_WORDS = 17u;

// The early 40 B guide and both later 24 B light/normal records share one 48 B pixel region.
// Branch-major light records overlapped guides belonging to other pixels, including opaque paths
// whose previous receiver normal was already live during the guide phase.
public uint primeRealtimePhaseWord(uint pathIndex, uint pixels) {
    uint branch = uint(pathIndex >= pixels);
    uint pixel = pathIndex - branch * pixels;
    uint precedingWords = pixels * (PRIME_WAVEFRONT_AREA_GUIDE_RECORD_SIZE / 4u
            + PRIME_WAVEFRONT_BRANCH_SLOTS_PER_PIXEL * (PRIME_WAVEFRONT_SURFACE_RECORD_SIZE / 4u));
    return precedingWords + (pixel * 2u + branch) * (PRIME_WAVEFRONT_STAGED_LIGHT_RECORD_SIZE / 4u);
}
