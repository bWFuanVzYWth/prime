// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/dielectric/contract.slang";

import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";


public static const float3 PRIME_COMPACT_TRANSMISSION_WAVELENGTHS_NM =
        float3(630.0, 532.0, 467.0);

public struct PrimeCompactTransmissionState {
    public PrimeOpenPbrOnb onb;
    public uint geometryThinWalled;
    public uint entering;
    public float rayT;
    public float originalIor;
    public float3 transmissionColor;
    public PrimeOpenPbrMicrofacet specularMicrofacet;
    public PrimeOpenPbrSpecularFresnel specularFresnel;
    public PrimeOpenPbrMicrofacet transmissionMicrofacet;
    public float3 transmissionTint;
    public PrimeOpenPbrVolume transmissionVolume;
    public float3 transmissionMultipleScattering;
};

public struct PrimeCompactTransmissionEvaluation {
    public PrimeOpenPbrThroughput throughput;
    public float pdf;
};

public float3 primeCompactTransmissionMaterialFrame(
        float3 w,
        PrimeCompactTransmissionState state) {
    return state.entering != 0u ? w : float3(-w.x, w.y, -w.z);
}
