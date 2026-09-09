// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/opaque/state.slang";

import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/sample.slang";


public static const uint PRIME_COMPACT_OPAQUE_DIELECTRIC = 0u;
public static const uint PRIME_COMPACT_OPAQUE_CONDUCTOR = 1u;
public static const uint PRIME_COMPACT_OPAQUE_SUBSURFACE = 2u;
public static const uint PRIME_COMPACT_OPAQUE_MIXED_SUBSURFACE = 3u;

public struct PrimeCompactMixState {
    public float firstSampleWeight;
    public float secondSampleWeight;
};

public struct PrimeCompactLayerState {
    public float3 transmittanceIn;
    public float baseSampleWeight;
    public float specularSampleWeight;
};

public struct PrimeCompactOpaqueState {
    public PrimeOpenPbrOnb onb;
    public uint kind;
    public float rayT;
    public float3 baseColor;
    public float subsurfaceWeight;
    public float3 subsurfaceColor;
    public float subsurfaceAnisotropy;
    public PrimeOpenPbrMicrofacet microfacet;
    public PrimeOpenPbrSpecularFresnel dielectricFresnel;
    public float3 dielectricMultipleScattering;
    public PrimeOpenPbrConductorFresnel conductorFresnel;
    public float conductorEss;
    public PrimeCompactMixState diffuseMix;
    public PrimeCompactLayerState layer;
};

public struct PrimeCompactOpaqueEvaluation {
    public PrimeOpenPbrThroughput throughput;
    public float pdf;
};

public struct PrimeCompactOpaqueComponents {
    public float3 diffuse;
    public float3 specular;
    public float pdf;
};

public struct PrimeCompactOpaqueEnergy {
    public float3 diffuse;
    public float3 specular;
};
