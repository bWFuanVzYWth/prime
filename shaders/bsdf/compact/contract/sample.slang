// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/contract/sample.slang";

import "bsdf/compact/contract/flags.slang";

public struct PrimeOpenPbrThroughput {
    public float3 value;
    public uint flags;
};

public struct PrimeOpenPbrSample {
    public PrimeOpenPbrThroughput throughput;
    public float pdf;
    public float eta;
    public float3 wo;
};

public struct PrimeOpenPbrFloatPair { public float first; public float second; };
public struct PrimeOpenPbrVecPair { public float3 first; public float3 second; };

public struct PrimeOpenPbrRefractResult {
    public float3 wo;
    public float relativeIor;
    public uint valid;
};

public struct PrimeOpenPbrMicrofacet { public float2 alpha; };

public struct PrimeOpenPbrSpecularFresnel {
    public float ior;
    public float energyIor;
    public float3 color;
};

public struct PrimeOpenPbrConductorFresnel {
    public float3 f0;
    // Relative Schlick tint at RoboCute's 1/7 cosine anchor, not absolute F(82 degrees).
    public float3 f82;
    public float3 f90;
    public float weight;
    public float3 energyF0;
};

public PrimeOpenPbrThroughput primeOpenPbrZeroThroughput() {
    PrimeOpenPbrThroughput result;
    result.value = float3(0.0);
    result.flags = PRIME_OPENPBR_FLAG_NONE;
    return result;
}

public PrimeOpenPbrSample primeOpenPbrZeroSample() {
    PrimeOpenPbrSample result;
    result.throughput = primeOpenPbrZeroThroughput();
    result.pdf = 0.0;
    result.eta = 1.0;
    result.wo = float3(0.0);
    return result;
}

public PrimeOpenPbrThroughput primeOpenPbrThroughputAdd(
        PrimeOpenPbrThroughput a,
        PrimeOpenPbrThroughput b) {
    PrimeOpenPbrThroughput result;
    result.value = a.value + b.value;
    result.flags = a.flags | b.flags;
    return result;
}

public PrimeOpenPbrThroughput primeOpenPbrThroughputScale(
        PrimeOpenPbrThroughput value,
        float3 scale) {
    value.value *= scale;
    if (all(scale == float3(0.0))) value.flags = PRIME_OPENPBR_FLAG_NONE;
    return value;
}

public PrimeOpenPbrThroughput primeOpenPbrThroughputScale(
        PrimeOpenPbrThroughput value,
        float scale) {
    value.value *= scale;
    if (scale == 0.0) value.flags = PRIME_OPENPBR_FLAG_NONE;
    return value;
}
