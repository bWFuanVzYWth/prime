// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/realtime/trace.slang";

import "state/realtime/contract.slang";
import "prime_abi_types.slang";
import "service/trace/surface.slang";

public SurfaceInteraction primeFusedWavefrontTrace(uint recordIndex) {
    float3 physicalOrigin = primeWavefrontPaths[primeWavefrontTransportIndex(recordIndex)]
            .transport.physicalOriginAndPreviousBsdfPdf.xyz;
    uint2 sourcePrimitive = primeWavefrontPaths[primeWavefrontTransportIndex(recordIndex)]
            .transport.sourceAndPathControl.xy;
    float3 rayDirection = primeWavefrontPaths[primeWavefrontTransportIndex(recordIndex)]
            .transport.rayDirectionAndDenoiserControl.xyz;
    uint traceControl = asuint(
            primeWavefrontPaths[primeWavefrontTransportIndex(recordIndex)]
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

