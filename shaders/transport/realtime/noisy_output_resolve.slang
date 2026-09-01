#language slang 2026
module "transport/realtime/noisy_output_resolve.slang";

import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "service/camera/projection.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";

import "state/realtime/contract.slang";
import "service/reconstruct/output.slang";

import "contract/realtime/ordinary.slang";
import "contract/realtime/resolved.slang";
import "service/reconstruct/resolved.slang";

public void primeResolveNoisyOutput(
        uint2 launchIndex) {
    // Resolve owns reconstruction only; transport has already completed.
    uint2 pixel = launchIndex;
    if (primeWavefrontPixelTransparent(pixel)) {
        return;
    }
    uint pathIndex = primeWavefrontIndex(pixel, 0u);
    uint denoiserControl = asuint(
            primeWavefrontPaths[pathIndex]
                    .transport.rayDirectionAndDenoiserControl.w);
    PrimeOrdinarySample ordinary = primeLoadWavefrontIntermediate(
            pixel, pathIndex, denoiserControl);
    PrimeResolvedSample result = primeResolveOrdinarySample(ordinary);
    float2 cameraSample = primeRealtimeCameraSample(pixel);
    primeWriteRealtimeOutput(pixel, cameraSample, result);
}
