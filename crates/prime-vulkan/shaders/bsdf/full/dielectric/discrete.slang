// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/dielectric/discrete.slang";

import "bsdf/full/contract/volume_sample.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/contract/volume.slang";
import "bsdf/full/dielectric/contract.slang";
import "bsdf/full/dielectric/thin_wall.slang";
import "bsdf/full/dielectric/volume.slang";
import "bsdf/full/math/fresnel.slang";
import "bsdf/full/math/delta.slang";
import "bsdf/full/math/spectrum.slang";

public PrimeFullOpenPbrSample primeFullCompactRefractiveDiscreteSample(
        float3 wi, float randomEvent, PrimeFullCompactTransmissionState state, uint samplingFlags) {
    return primeFullRefractiveDeltaSample(wi, randomEvent, state.specularFresnel, samplingFlags);
}

public PrimeFullOpenPbrSample primeFullCompactThinWallDiscreteSampleRaw(
        float3 wi, float randomEvent, PrimeFullCompactTransmissionState state, uint samplingFlags) {
    return primeFullThinWallDeltaSample(wi, randomEvent, state.specularFresnel,
                                        state.transmissionTint, state.transmissionVolume.extinction,
                                        samplingFlags);
}

public PrimeFullOpenPbrSampleResult
primeFullCompactTransmissionDiscreteSample(float3 wi, float randomEvent,
                                           PrimeFullCompactTransmissionState state,
                                           uint samplingFlags, PrimeFullOpenPbrVolume medium) {
    PrimeFullCompactTransmissionState closureState = state;
    if (closureState.geometryThinWalled == 0u) {
        closureState.entering = 1u;
    }
    PrimeFullOpenPbrSampleResult result;
    result.medium = medium;
    result.rayT = state.rayT;
    if (closureState.geometryThinWalled != 0u) {
        result.bsdfSample = primeFullCompactThinWallDiscreteSampleRaw(wi, randomEvent, closureState,
                                                                      samplingFlags);
    } else {
        result.bsdfSample = primeFullCompactRefractiveDiscreteSample(
                primeFullCompactTransmissionMaterialFrame(wi, closureState), randomEvent,
                closureState, samplingFlags);
        if (result.bsdfSample.throughput.flags != PRIME_FULL_OPENPBR_FLAG_NONE) {
            result.bsdfSample.wo =
                    primeFullCompactTransmissionMaterialFrame(result.bsdfSample.wo, closureState);
            if (primeFullOpenPbrIsTransmissive(result.bsdfSample.throughput.flags)) {
                result.bsdfSample.throughput.value *= closureState.transmissionTint;
            }
        }
    }
    primeFullCompactApplyTransmissionVolumeTransition(state, result);
    return result;
}
