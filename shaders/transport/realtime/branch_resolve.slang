#language slang 2026
module "transport/realtime/branch_resolve.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/realtime/guide.slang";
import "service/camera/projection.slang";
import "policy/runtime/config.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/queue.slang";
import "service/reconstruct/output.slang";
import "state/realtime/resolve.slang";
import "model/realtime/guide.slang";

import "contract/realtime/resolved.slang";
import "service/reconstruct/resolved.slang";

public void primeResolveRealtimeBranch(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE, launchIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    uint reflectionControl = asuint(
            primeWavefrontPaths[primeWavefrontIndex(pixel, 1u)]
                    .transport.rayDirectionAndDenoiserControl.w);

    float2 cameraSample = primeRealtimeCameraSample(pixel);
    bool guideModeEnabled = primeTransparentGuideMode()
            != PRIME_PATH_TRANSPARENT_GUIDE_MODE_DISABLED;
    // Resolve after every probe trace so the complete radiance and guide result does not remain
    // live across traversal. The probes are reconstruction-only and cannot affect this load.
    PrimeResolvedSample result =
            primeResolveTransparentWavefrontResult(pixel);
    float4 reflectionPreviousVirtualPosition =
            primeCapturedReflectionPreviousVirtualPosition(
                    result,
                    (reflectionControl & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE)
                            != 0u);
    if (!guideModeEnabled) {
        // Full mode historically ignored its queued TIR marker when transparent reconstruction was
        // disabled; keep that output topology independent of this probe-elision path.
        result.transparentReflectionOnly = false;
    }
    if (result.transparentReflectionOnly) {
        // The transmission identity does not exist under TIR. Keeping the visible glass albedo in
        // this lane asks the denoiser to reconstruct a signal that is exactly zero.
        result.transmissionGuides = primeEmptyDenoiserGuides();
        result.transmissionAnchorDistance = -1.0;
    }
    primeWriteRealtimeOutput(pixel,
            cameraSample,
            result,
            reflectionPreviousVirtualPosition);
}
