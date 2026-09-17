// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/area.slang";

import "state/realtime/contract.slang";
import "prime_realtime_abi.slang";
import "math/reconstruction_scratch.slang";
import "math/packed_geometry.slang";
import "contract/realtime/guide.slang";
import "service/reconstruct/nrd/common.slang";

public static const uint PRIME_WAVEFRONT_AREA_GUIDE_WORDS =
        PRIME_WAVEFRONT_AREA_GUIDE_RECORD_SIZE / 4u;

public uint primeWavefrontAreaWord(uint2 pixel, uint component) {
    return primeReconstructionGuideWord(primeWavefrontIndex(pixel), component);
}

public uint3 primePackWavefrontPair(float3 first, float3 second) {
    return uint3(
            packHalf2x16(first.xy),
            packHalf2x16(float2(first.z, second.x)),
            packHalf2x16(second.yz));
}

public void primeUnpackWavefrontPair(uint3 packedPair, out float3 first, out float3 second) {
    float2 first01 = unpackHalf2x16ToFloat(packedPair.x);
    float2 first2Second0 = unpackHalf2x16ToFloat(packedPair.y);
    float2 second12 = unpackHalf2x16ToFloat(packedPair.z);
    first = float3(first01, first2Second0.x);
    second = float3(first2Second0.y, second12);
}

public void primeStoreWavefrontArea(uint2 pixel, PrimeDenoiserGuides guides) {
    uint3 radiance = primePackWavefrontPair(
            primeNrdSanitizeRadiance(guides.primaryAreaDiffuse),
            primeNrdSanitizeRadiance(guides.primaryAreaSpecular));
    float3 direction = guides.primaryAreaDirection;
    if (!(dot(direction, direction) > 0.0)) {
        direction = float3(0.0, 1.0, 0.0);
    }
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 0u)] = radiance.x;
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 1u)] = radiance.y;
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 2u)] = radiance.z;
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 3u)] =
            primePackOctahedralUnitVector(direction);
}

public void primeLoadWavefrontArea(
        uint2 pixel,
        out float3 diffuse,
        out float3 specular,
        out float3 direction) {
    uint3 radiance = uint3(
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 0u)],
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 1u)],
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 2u)]);
    primeUnpackWavefrontPair(radiance, diffuse, specular);
    direction = primeUnpackOctahedralUnitVector(
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 3u)]);
}
