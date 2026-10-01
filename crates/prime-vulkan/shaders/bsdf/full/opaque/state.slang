// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/opaque/state.slang";

import "bsdf/full/contract/material.slang";
import "bsdf/full/contract/sample.slang";

public static const uint PRIME_FULL_COMPACT_OPAQUE_DIELECTRIC = 0u;
public static const uint PRIME_FULL_COMPACT_OPAQUE_CONDUCTOR = 1u;
public static const uint PRIME_FULL_COMPACT_OPAQUE_SUBSURFACE = 2u;
public static const uint PRIME_FULL_COMPACT_OPAQUE_MIXED_SUBSURFACE = 3u;

public struct PrimeFullCompactMixState {
    public float firstSampleWeight;
    public float secondSampleWeight;
};

public struct PrimeFullCompactLayerState {
    public float3 transmittanceIn;
    public float baseSampleWeight;
    public float specularSampleWeight;
};

public struct PrimeFullCompactOpaqueState {
    public PrimeFullOpenPbrOnb onb;
    public uint kind;
    public float3 baseColor;
    public float subsurfaceWeight;
    public float3 subsurfaceColor;
    public float subsurfaceAnisotropy;
    public PrimeFullOpenPbrMicrofacet microfacet;
    public PrimeFullOpenPbrSpecularFresnel dielectricFresnel;
    public float3 dielectricMultipleScattering;
    public PrimeFullOpenPbrConductorFresnel conductorFresnel;
    public float conductorEss;
    public PrimeFullCompactMixState diffuseMix;
    public PrimeFullCompactLayerState layer;
};

public struct PrimeFullCompactOpaqueEvaluation {
    public PrimeFullOpenPbrThroughput throughput;
    public float pdf;
};

public struct PrimeFullCompactOpaqueComponents {
    public float3 diffuse;
    public float3 specular;
    public float pdf;
};

public struct PrimeFullCompactOpaqueEnergy {
    public float3 diffuse;
    public float3 specular;
};
