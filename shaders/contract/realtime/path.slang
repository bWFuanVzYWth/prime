// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/realtime/path.slang";

import "contract/path.slang";

// Two signal slots belong to each realtime pixel so transparent reflection and transmission
// keep independent reconstruction histories. The previous receiver normal remains exact f32 in
// phase scratch until the direct stage consumes its reverse-MIS query.
public struct PrimeWavefrontTransportRecord {
    public float4 physicalOriginAndPreviousBsdfPdf;
    public uint4 sourceAndPathControl;
    public float4 rayDirectionAndDenoiserControl;
    public float4 throughputAndReserved;
    public uint4 medium;
};

// 80 bytes of transport are followed by exact queued PSR/previous-position data: 96 bytes in
// total. Exact branch guide normals live in the already-resident RGBA32F reconstruction images.
public struct PrimeWavefrontPathRecord {
    public PrimeWavefrontTransportRecord transport;
    public uint4 psrPacked;
};

public static const uint PRIME_WAVEFRONT_REACHED_SOLID_ANGLE = 2u;
public static const uint PRIME_WAVEFRONT_DIFFUSE_PATH = 4u;
public static const uint PRIME_WAVEFRONT_TRANSPARENT_BRANCH = 8u;
public static const uint PRIME_WAVEFRONT_TRANSMISSION_BRANCH = 16u;
public static const uint PRIME_WAVEFRONT_GUIDE_ENABLED = 32u;
public static const uint PRIME_WAVEFRONT_DIRECTIONAL_GUIDE = 64u;
public static const uint PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY = 128u;
public static const uint PRIME_WAVEFRONT_BRANCH_CONTROL_MASK = 0xf8u;
public static const uint PRIME_WAVEFRONT_BOUNCE_SHIFT = 0u;
public static const uint PRIME_WAVEFRONT_PATH_FLAGS_SHIFT = 24u;
public static const uint PRIME_WAVEFRONT_PATH_FLAGS_MASK =
        PRIME_PATH_PREVIOUS_DISCRETE
        | PRIME_PATH_PREVIOUS_NO_SUN_NEE;
public static const uint PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT = 8u;
// Bits 18..31 are the complete current material-flag domain. Extending material flags beyond
// bit 13 requires a queue ABI migration rather than silent truncation here.
public static const uint PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT = 18u;
public static const uint PRIME_WAVEFRONT_BYTE_MASK = 0xffu;
public static const uint PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK = 0x3fffu;
public static const uint PRIME_WAVEFRONT_PSR_CONTROL_MASK = 0x3u;
// The low guide lanes are disjoint from exact medium IOR-source encodings in this word. During
// delta walk fallback means detached; later phases reuse it after abandoning an invalid PSR guide.
public static const uint PRIME_WAVEFRONT_GUIDE_FALLBACK = 0x4u;
public static const uint PRIME_WAVEFRONT_GUIDE_DETACHED =
        PRIME_WAVEFRONT_GUIDE_FALLBACK;
public static const uint PRIME_WAVEFRONT_PRIMARY_MOTION = 1u;

public struct PrimeWavefrontContinuationControl {
    public uint guide;
    public uint path;
    public uint denoiser;
};
