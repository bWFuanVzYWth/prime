// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/contract/sample.slang";

import "bsdf/full/contract/flags.slang";

public struct PrimeFullOpenPbrThroughput {
    public float3 value;
    public uint flags;
};

public struct PrimeFullOpenPbrSample {
    public PrimeFullOpenPbrThroughput throughput;
    public float pdf;
    public float eta;
    public float3 wo;
};

public struct PrimeFullOpenPbrFloatPair {
    public float first;
    public float second;
};
public struct PrimeFullOpenPbrVecPair {
    public float3 first;
    public float3 second;
};

public struct PrimeFullOpenPbrRefractResult {
    public float3 wo;
    public float relativeIor;
    public uint valid;
};

public struct PrimeFullOpenPbrMicrofacet {
    public float2 alpha;
};

public struct PrimeFullOpenPbrSpecularFresnel {
    public float ior;
    public float energyIor;
    public float3 color;
};

public struct PrimeFullOpenPbrConductorFresnel {
    public float3 f0;
    // Relative Schlick tint at RoboCute's 1/7 cosine anchor, not absolute F(82 degrees).
    public float3 f82;
    public float3 f90;
    public float weight;
    public float3 energyF0;
};

public PrimeFullOpenPbrThroughput primeFullOpenPbrZeroThroughput() {
    PrimeFullOpenPbrThroughput result;
    result.value = float3(0.0);
    result.flags = PRIME_FULL_OPENPBR_FLAG_NONE;
    return result;
}

public PrimeFullOpenPbrSample primeFullOpenPbrZeroSample() {
    PrimeFullOpenPbrSample result;
    result.throughput = primeFullOpenPbrZeroThroughput();
    result.pdf = 0.0;
    result.eta = 1.0;
    result.wo = float3(0.0);
    return result;
}

public PrimeFullOpenPbrThroughput primeFullOpenPbrThroughputAdd(PrimeFullOpenPbrThroughput a,
                                                                PrimeFullOpenPbrThroughput b) {
    PrimeFullOpenPbrThroughput result;
    result.value = a.value + b.value;
    result.flags = a.flags | b.flags;
    return result;
}

public PrimeFullOpenPbrThroughput primeFullOpenPbrThroughputScale(PrimeFullOpenPbrThroughput value,
                                                                  float3 scale) {
    value.value *= scale;
    if (all(scale == float3(0.0)))
        value.flags = PRIME_FULL_OPENPBR_FLAG_NONE;
    return value;
}

public PrimeFullOpenPbrThroughput primeFullOpenPbrThroughputScale(PrimeFullOpenPbrThroughput value,
                                                                  float scale) {
    value.value *= scale;
    if (scale == 0.0)
        value.flags = PRIME_FULL_OPENPBR_FLAG_NONE;
    return value;
}
