#language slang 2026
module "transport/realtime/trace.slang";

import "state/realtime/contract.slang";
import "prime_abi_types.slang";
import "service/trace/surface.slang";

public SurfaceInteraction primeFusedWavefrontTrace(uint recordIndex) {
    float3 physicalOrigin = primeWavefrontPaths[recordIndex]
            .transport.physicalOriginAndPreviousBsdfPdf.xyz;
    uint2 sourcePrimitive = primeWavefrontPaths[recordIndex]
            .transport.sourceAndPathControl.xy;
    float3 rayDirection = primeWavefrontPaths[recordIndex]
            .transport.rayDirectionAndDenoiserControl.xyz;
    uint traceControl = asuint(
            primeWavefrontPaths[recordIndex]
                    .transport.rayDirectionAndDenoiserControl.w);
    bool transparent = primeWavefrontTransparentBranch(traceControl);
    uint pathClass = !transparent
            ? 0u
            : primeWavefrontTransmissionBranch(traceControl) ? 2u : 1u;
    SurfaceInteraction surface = primeTraceSurfaceClassified(
            PrimeSurfaceTracePolicy(),
            physicalOrigin,
            rayDirection,
            sourcePrimitive,
            pathClass);
    return surface;
}

