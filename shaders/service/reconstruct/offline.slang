// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/reconstruct/offline.slang";

import "prime_offline_abi.slang";
import "service/atmosphere/trace.slang";
import "service/reconstruct/auto_exposure.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";
import "state/offline/contract.slang";

public void primeWriteOfflineOutput(
        uint2 pixel,
        float2 cameraSample,
        PrimeOfflinePathRecord record) {
    float3 radiance = record.radianceAndPrimaryDistance.xyz;
    float primaryDistance = record.radianceAndPrimaryDistance.w;
    radiance = primeApplyAerialPerspective(
            pixel, cameraSample, primaryDistance, radiance);
    uint materialControl = asuint(
            record.primaryAlbedoAndMaterialFlags.w);
    float confidence = primeAutoExposureMaterialConfidence(
            uint(round(primeNrdMaterialId(materialControl) * 3.0)),
            primaryDistance);
    float meteredBrightness = primeAutoExposureMeteredBrightness(
            radiance,
            primeNrdSanitizeAlbedo(record.primaryAlbedoAndMaterialFlags.xyz),
            confidence);
    uint64_t zeroBasedSample = (uint64_t(primeSampleEpoch()) << 16u)
            | uint64_t(primeSampleIndex());
    float4 mean = float4(radiance, meteredBrightness);
    if (zeroBasedSample != uint64_t(0)) {
        float4 previous = primeImageLoad(primeOfflineRunningMean, int2(pixel));
        float sampleCount = float(zeroBasedSample + uint64_t(1));
        mean = previous + (mean - previous) / sampleCount;
    }
    primeImageStore(primeOfflineRunningMean, int2(pixel), mean);
}

