#language slang 2026
module "math/z_sobol.slang";

// Adapted from the author's z_sobol WGSL implementation. Fast Owen derives from
// PBRT-v4 (Apache-2.0); see THIRD_PARTY_NOTICES.md and licenses/pbrt-Apache-2.0.txt.
// No descriptors, tables, 64-bit arithmetic or hidden per-pixel seed.
uint avalanche(uint value) {
    value ^= value >> 16;
    value *= 0x7feb352du;
    value ^= value >> 15;
    value *= 0x846ca68bu;
    return value ^ (value >> 16);
}
uint spread(uint value) {
    value &= 0xffffu;
    value = (value | (value << 8)) & 0x00ff00ffu;
    value = (value | (value << 4)) & 0x0f0f0f0fu;
    value = (value | (value << 2)) & 0x33333333u;
    return (value | (value << 1)) & 0x55555555u;
}
uint permute4(uint digit, uint hash) {
    uint pair = ((hash >> 8) * 6u) >> 24;
    uint a = (0x0fa5u >> (2u * pair)) & 3u;
    uint b = (0x09deu >> (2u * pair)) & 3u;
    return ((digit & 1u) * a) ^ ((digit >> 1) * b) ^ (hash & 3u);
}
uint nodeHash(uint2 prefix, uint shift, uint key) {
    return avalanche(prefix.x ^ (prefix.y * 0x9e3779b9u) ^ key ^ (shift * 0x632be59bu));
}
uint2 permutedIndex(uint2 morton, uint bits, uint odd, uint key) {
    uint2 result = uint2(0);
    if (bits <= 32) {
        uint m = morton.x >> odd;
        if (odd != 0)
            result.x = (morton.x & 1u) ^ (nodeHash(uint2(m, 0), 0, key) & 1u);
        for (uint shift = odd; shift < bits; shift += 2) {
            uint digit = m & 3u;
            m >>= 2;
            result.x |= permute4(digit, nodeHash(uint2(m, 0), shift, key)) << shift;
        }
    } else {
        uint2 m = morton;
        if (odd != 0) {
            m = uint2((m.x >> 1) | (m.y << 31), m.y >> 1);
            result.x = (morton.x & 1u) ^ (nodeHash(m, 0, key) & 1u);
        }
        // Fixed two-bit shifts avoid the generic 64-bit-shift helper's branches.
        for (uint shift = odd; shift < bits; shift += 2) {
            uint digit = m.x & 3u;
            m = uint2((m.x >> 2) | (m.y << 30), m.y >> 2);
            uint d = permute4(digit, nodeHash(m, shift, key));
            if (shift < 32) {
                result.x |= d << shift;
                if (shift == 31)
                    result.y |= d >> 1;
            } else {
                result.y |= d << (shift - 32);
            }
        }
    }
    return result;
}
// Operate on reversed Sobol bits directly: cancel the adjacent Sobol/Owen reversals.
uint reversedSobolY(uint v) {
    v ^= (v >> 1) & 0x55555555u;
    v ^= (v >> 2) & 0x33333333u;
    v ^= (v >> 4) & 0x0f0f0f0fu;
    v ^= (v >> 8) & 0x00ff00ffu;
    v ^= (v >> 16) & 0x0000ffffu;
    return v;
}
uint owenReversed(uint v, uint seed) {
    v ^= v * 0x3d20adeau;
    v += seed;
    v *= (seed >> 16) | 1u;
    v ^= v * 0x05526c56u;
    v ^= v * 0x53a22864u;
    return reversebits(v);
}
public struct ZSobol {
    internal uint2 morton;
    internal uint bitCount;
    internal uint odd;
    internal uint seed;

    public uint2 bits2D(uint domain) {
        uint key = avalanche(domain ^ seed);
        uint2 index = permutedIndex(morton, bitCount, odd, key);
        return uint2(owenReversed(index.x, avalanche(key ^ 0xa511e9b3u)),
                     owenReversed(reversedSobolY(index.x ^ index.y), avalanche(key ^ 0x63d83595u)));
    }
    public uint bits1D(uint domain) {
        uint key = avalanche(domain ^ seed);
        uint2 index = permutedIndex(morton, bitCount, odd, key);
        return owenReversed(index.x, avalanche(key ^ 0xa511e9b3u));
    }
    public float2 sample2D(uint domain) {
        return float2(bits2D(domain) >> 8) * (1.0 / 16777216.0);
    }
    public float sample1D(uint domain) {
        return float(bits1D(domain) >> 8) * (1.0 / 16777216.0);
    }
};

// Boundary contract: R<=16, S<=20, 2R+S<=52; pixel<2^R, sample<2^S.
// Keep R/S/domain/seed fixed for one sample set. Validate once outside the sampling loop.
public ZSobol makeZSobol(uint2 pixel, uint sample, uint log2Resolution, uint log2Spp, uint seed) {
    uint m = spread(pixel.x) | (spread(pixel.y) << 1);
    ZSobol result;
    if (log2Spp == 0)
        result.morton = uint2(m, 0);
    else
        result.morton = uint2((m << log2Spp) | sample, m >> (32 - log2Spp));
    result.bitCount = 2 * log2Resolution + log2Spp;
    result.odd = log2Spp & 1u;
    result.seed = seed;
    return result;
}

// Production uses S=8 and sample<256. Keep the public variable-S constructor
// above for its full boundary contract and independent sampling fixtures.
public ZSobol makeZSobolS8(uint2 pixel, uint sample, uint log2Resolution, uint seed) {
    uint m = spread(pixel.x) | (spread(pixel.y) << 1);
    ZSobol result;
    result.morton = uint2((m << 8) | sample, m >> 24);
    result.bitCount = 2 * log2Resolution + 8;
    result.odd = 0;
    result.seed = seed;
    return result;
}
