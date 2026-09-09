// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/direct_sampling.slang";

import "prime_abi_bindings.slang";

public static const uint PRIME_DIRECT_SAMPLE_AREA_PRIMARY = 0u;
public static const uint PRIME_DIRECT_SAMPLE_AREA_SECONDARY = 1u;
public static const uint PRIME_DIRECT_SAMPLE_SUN = 2u;

static const uint PRIME_STBN_WIDTH_MASK = PRIME_REALTIME_STBN_WIDTH - 1u;
static const uint PRIME_STBN_FRAME_MASK = PRIME_REALTIME_STBN_DEPTH - 1u;
// log2(128 * 128) and log2(128 * 128 * 64), pinned by the generated ABI.
static const uint PRIME_STBN_FRAME_SHIFT = 14u;
static const uint PRIME_STBN_BANK_SHIFT = 20u;
static const float PRIME_STBN_SCALE = 1.0 / 65536.0;

[[vk::binding(PRIME_REALTIME_STBN_BINDING, 0)]]
public StructuredBuffer<uint> primeRealtimeStbn;

public struct PrimeDirectSampleBase
{
    public uint texel;
    public uint frame;
};

uint primeStbnAvalanche(uint value)
{
    value ^= value >> 16u;
    value *= 0x7feb352du;
    value ^= value >> 15u;
    value *= 0x846ca68bu;
    value ^= value >> 16u;
    return value;
}

public PrimeDirectSampleBase primeMakeDirectSampleBase(
        uint2 pixel,
        uint sampleIndex,
        uint sampleEpoch,
        uint vertexIndex,
        uint pathBranch)
{
    // FAST preserves each 64-sample temporal column; only complete columns may
    // change their spatial origin when extending the finite table.
    uint cycle = sampleIndex >> 6u;
    uint pathPair = vertexIndex * 2u + pathBranch;
    uint offsetSeed = primeStbnAvalanche(sampleEpoch);
    // Both odd multipliers are bijections modulo 2^14: blocks visit distinct
    // offsets, and the bounded bounce/branch domain cannot collide within a frame.
    uint offset = (cycle * 0x2c2bu + pathPair * 0x01f5u + offsetSeed)
            & 0x3fffu;
    uint2 tiledPixel = (pixel + uint2(offset, offset >> 7u))
            & PRIME_STBN_WIDTH_MASK;
    PrimeDirectSampleBase result;
    result.texel = tiledPixel.y * PRIME_REALTIME_STBN_WIDTH + tiledPixel.x;
    result.frame = sampleIndex & PRIME_STBN_FRAME_MASK;
    return result;
}

public uint primeStbnSampleAddress(PrimeDirectSampleBase base, uint stream)
{
    return (stream << PRIME_STBN_BANK_SHIFT)
            + (base.frame << PRIME_STBN_FRAME_SHIFT) + base.texel;
}

public float2 primeDecodeStbnSample(uint packed)
{
    uint2 value = uint2(packed & 0xffffu, packed >> 16u);
    return (float2(value) + 0.5) * PRIME_STBN_SCALE;
}

public float2 primeDirectSample2D(PrimeDirectSampleBase base, uint stream)
{
    return primeDecodeStbnSample(primeRealtimeStbn[primeStbnSampleAddress(base, stream)]);
}
