// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/dielectric/contract.slang";

import "bsdf/full/contract/material.slang";
import "bsdf/full/contract/volume.slang";
import "bsdf/full/contract/sample.slang";

public static const float3 PRIME_FULL_COMPACT_TRANSMISSION_WAVELENGTHS_NM =
        float3(630.0, 532.0, 467.0);

public struct PrimeFullCompactTransmissionState {
    public PrimeFullOpenPbrOnb onb;
    public uint geometryThinWalled;
    public uint entering;
    public float rayT;
    public float originalIor;
    public float3 transmissionColor;
    public PrimeFullOpenPbrMicrofacet specularMicrofacet;
    public PrimeFullOpenPbrSpecularFresnel specularFresnel;
    public PrimeFullOpenPbrMicrofacet transmissionMicrofacet;
    public float3 transmissionTint;
    public PrimeFullOpenPbrVolume transmissionVolume;
    public float3 transmissionMultipleScattering;
};

public struct PrimeFullCompactTransmissionEvaluation {
    public PrimeFullOpenPbrThroughput throughput;
    public float pdf;
};

public float3 primeFullCompactTransmissionMaterialFrame(float3 w,
                                                        PrimeFullCompactTransmissionState state) {
    return state.entering != 0u ? w : float3(-w.x, w.y, -w.z);
}
