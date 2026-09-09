// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/foliage/contract.slang";

import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/contract/sample.slang";

public struct PrimeCompactFoliageState {
    public PrimeCompactOpaqueState base;
    public PrimeOpenPbrMicrofacet transmissionMicrofacet;
    public float3 transmissionTint;
    public PrimeCompactMixState dielectricMix;
    public float transmissionWeight;
};

public struct PrimeCompactFoliageEvaluation {
    public PrimeOpenPbrThroughput throughput;
    public float pdf;
};

public struct PrimeCompactFoliageComponents {
    public float3 diffuse;
    public float3 specular;
    public float pdf;
};

public struct PrimeCompactFoliageEnergy {
    public float3 diffuse;
    public float3 specular;
};
