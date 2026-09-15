// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

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

PrimeOpenPbrVolume primeCompactTestVacuum() {
    return primeVacuumMedium();
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
