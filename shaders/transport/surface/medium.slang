#language slang 2026
module "transport/surface/medium.slang";

import "prime_abi_types.slang";
import "policy/runtime/config.slang";
import "math/transport.slang";
import "service/light/emission.slang";
import "model/material/defaults.slang";
import "service/trace/context.slang";
import "bsdf/compact/contract/volume.slang";

public float3 primeEvaluateDeltaHitEmission(
        inout PrimeIntegratorContext context,
        PathState path,
        SurfaceInteraction surface) {
    float3 radiance =
            (surface.materialControl & PRIME_MATERIAL_VISIBLE_EMISSION) != 0u
            ? surface.baseColor
                    * PRIME_LEVEL_15_BLOCK_INTENSITY
                    * primeBlockLightRadianceMultiplier()
            : primeEvaluateHitEmitterRadiance(surface, path.rayDirection);
    return path.throughput * radiance;
}

public bool primeApplySegmentMedium(
        inout float3 throughput,
        SurfaceInteraction surface,
        PrimeOpenPbrVolumeStack volumeStack) {
    if (volumeStack.count == 0u) {
        return true;
    }
    PrimeOpenPbrVolume medium = volumeStack.values[volumeStack.count - 1u];
    throughput *= primeSegmentTransmittance(medium.extinction, surface.t);
    return !all(throughput == float3(0.0));
}
