#language slang 2026
module "transport/scatter/path_update.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "service/bsdf/contract/sample.slang";
import "math/packed_geometry.slang";
import "math/queued_psr.slang";
import "math/transport.slang";
import "transport/surface/access.slang";
import "service/trace/context.slang";
import "contract/path.slang";
import "contract/material/normal.slang";

public bool primeHasScatter(BsdfSample bsdf) {
    return bsdf.eventFlags != 0u;
}

public bool primeIsDiscreteSample(BsdfSample bsdf) {
    return (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u;
}

public bool primeIsSolidAngleSample(BsdfSample bsdf) {
    uint solidAngleFlags =
            PRIME_BSDF_EVENT_DIFFUSE | PRIME_BSDF_EVENT_GLOSSY;
    return (bsdf.eventFlags & solidAngleFlags) != 0u
            && !primeIsDiscreteSample(bsdf);
}

public bool primeApplyPathScatter(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf) {
    if (!primeGeometricSampleSupport(
            primeMaterialUsesNormalMap(surface.opticalControl),
            surface.geometricNormal,
            -path.rayDirection,
            bsdf)) {
        return false;
    }
    float3 nextThroughput =
            primeProductOver(path.throughput, bsdf.response, bsdf.pdf);
    if (all(nextThroughput == float3(0.0))) {
        return false;
    }
    path.throughput = nextThroughput;
    path.etaScale = primeEtaScaleAfterScatter(
            path.etaScale,
            bsdf.relativeEta,
            (bsdf.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0u);
    path.physicalOrigin = surface.position;
    path.sourcePrimitive = uint2(
            surface.sectionIndex, primeSurfaceTriangleIndex(surface));
    path.rayDirection = bsdf.direction;
    path.previousBsdfPdf = bsdf.pdf;
    path.control = primePathWithFlags(
            path.control,
            (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u
                    ? PRIME_PATH_PREVIOUS_DISCRETE
                    : 0u);
    return true;
}

public bool primeAdvancePathWithoutRoulette(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        float3 previousLightNormal) {
    if (!primeApplyPathScatter(context, path, surface, bsdf)) {
        return false;
    }
    path.previousLightNormal = previousLightNormal;
    return true;
}

public PrimeQueuedPsrState primeAppendQueuedPsrDiscrete(
        PrimeQueuedPsrState state,
        float3 previousPosition,
        SurfaceInteraction surface,
        BsdfSample bsdf) {
    return primeAppendQueuedPsrState(
            state,
            primePush.cameraPosition,
            previousPosition,
            surface.position,
            surface.geometricNormal,
            (bsdf.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u);
}
