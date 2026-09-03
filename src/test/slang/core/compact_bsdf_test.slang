#ifndef PRIME_COMPACT_BSDF_TEST_SLANG
#define PRIME_COMPACT_BSDF_TEST_SLANG

float primeCompactTestParam(uint4 encoded[4], uint index) {
    uint flatIndex = index + 1u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

float3 primeCompactTestDirection(float cosineTheta, float phi) {
    float radius = sqrt(max(0.0, 1.0 - cosineTheta * cosineTheta));
    return float3(radius * cos(phi), radius * sin(phi), cosineTheta);
}

PrimeOpenPbrVolumeStack primeCompactTestEmptyStack() {
    PrimeOpenPbrVolumeStack stack;
    PrimeOpenPbrVolume air;
    air.extinction = float3(0.0);
    air.albedo = float3(0.0);
    air.anisotropy = 0.0;
    air.ior = 1.0;
    air.mediumId = 0u;
    air.iorEncoding = 0u;
    stack.values[0] = air;
    stack.values[1] = air;
    stack.count = 0u;
    return stack;
}

void primeCompactTestClassify(inout uint mask, float value) {
    mask |= primeTestClassify(value);
}

void primeCompactTestClassify(inout uint mask, float2 value) {
    mask |= primeTestClassify(value);
}

void primeCompactTestClassify(inout uint mask, float3 value) {
    mask |= primeTestClassify(value);
}

bool primeCompactTestNonnegative(float value) {
    return value >= 0.0 && primeTestClassify(value) == 0u;
}

bool primeCompactTestNonnegative(float3 value) {
    return all(value >= float3(0.0)) && primeTestClassify(value) == 0u;
}

#endif
