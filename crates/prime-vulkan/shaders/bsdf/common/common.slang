// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/common/common.slang";

// Shared material types and scalar utilities retained by the historical LitePBR model.
// No closure graph or energy resource is declared here.

public static const float PRIME_OPENPBR_PI = 3.14159265358979323846;

public static const float PRIME_OPENPBR_INV_PI = 0.31830988618379067154;

public static const float PRIME_OPENPBR_DENOM_TOLERANCE = 1.0e-10;

public static const uint PRIME_OPENPBR_FLAG_NONE = 0u;

public static const uint PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION = 1u << 0u;

public static const uint PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION = 1u << 1u;

public static const uint PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION = 1u << 2u;

public static const uint PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION = 1u << 3u;

public static const uint PRIME_OPENPBR_FLAG_DELTA_REFLECTION = 1u << 4u;

public static const uint PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION = 1u << 5u;

public static const uint PRIME_OPENPBR_FLAG_DIFFUSE =
        PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION | PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION;

public static const uint PRIME_OPENPBR_FLAG_SPECULAR =
        PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION | PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION;

public static const uint PRIME_OPENPBR_FLAG_DELTA =
        PRIME_OPENPBR_FLAG_DELTA_REFLECTION | PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION;

public static const uint PRIME_OPENPBR_FLAG_REFLECTION = PRIME_OPENPBR_FLAG_DIFFUSE_REFLECTION |
                                                         PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION |
                                                         PRIME_OPENPBR_FLAG_DELTA_REFLECTION;

public static const uint PRIME_OPENPBR_FLAG_TRANSMISSION =
        PRIME_OPENPBR_FLAG_DIFFUSE_TRANSMISSION | PRIME_OPENPBR_FLAG_SPECULAR_TRANSMISSION |
        PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION;

public static const uint PRIME_OPENPBR_FLAG_ALL =
        PRIME_OPENPBR_FLAG_REFLECTION | PRIME_OPENPBR_FLAG_TRANSMISSION;

public static const uint PRIME_OPENPBR_DETAIL_DEFAULT = 0u;

public static const uint PRIME_OPENPBR_DETAIL_INDIRECT_DIFFUSE = 2u;

public static const uint PRIME_OPENPBR_DIFFRACTION_RECTANGULAR = 1u;

public static const uint PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE = 2u;

public struct PrimeOpenPbrOnb {
    public float3 tangent;
    public float3 bitangent;
    public float3 normal;
};

public struct PrimeOpenPbrWeight {
    public float base;
    public float diffuseRoughness;
    public float specular;
    public float metalness;
    public float subsurface;
    public float transmission;
    public float coat;
    public float fuzz;
    public float thinFilm;
    public float diffraction;
};

public struct PrimeOpenPbrGeometry {
    public uint thinWalled;
    public float thickness;
    public PrimeOpenPbrOnb onb;
};

public struct PrimeOpenPbrSpecular {
    public float3 color;
    public float roughness;
    public float roughnessAnisotropy;
    public float ior;
};

public struct PrimeOpenPbrEmission {
    public float3 luminance;
};

public struct PrimeOpenPbrBase {
    public float3 color;
};

public struct PrimeOpenPbrSubsurface {
    public float3 color;
    public float3 radius;
    public float scatterAnisotropy;
};

public struct PrimeOpenPbrTransmission {
    public float3 color;
    public float depth;
    public float3 scatter;
    public float scatterAnisotropy;
    public float dispersionScale;
    public float dispersionAbbeNumber;
};

public struct PrimeOpenPbrCoat {
    public float3 color;
    public float roughness;
    public float roughnessAnisotropy;
    public float ior;
    public float darkening;
    public float roughening;
    public PrimeOpenPbrOnb onb;
};

public struct PrimeOpenPbrFuzz {
    public float3 color;
    public float roughness;
};

public struct PrimeOpenPbrThinFilm {
    public float thickness;
    public float ior;
};

public struct PrimeOpenPbrDiffraction {
    public float3 color;
    public float thickness;
    public float2 invPitch;
    public float angle;
    public uint lobeCount;
    public uint kind;
};

public struct PrimeOpenPbrMaterial {
    public PrimeOpenPbrWeight weight;
    public PrimeOpenPbrGeometry geometry;
    public PrimeOpenPbrSpecular specular;
    public PrimeOpenPbrEmission emission;
    public PrimeOpenPbrBase base;
    public PrimeOpenPbrSubsurface subsurface;
    public PrimeOpenPbrTransmission transmission;
    public PrimeOpenPbrCoat coat;
    public PrimeOpenPbrFuzz fuzz;
    public PrimeOpenPbrThinFilm thinFilm;
    public PrimeOpenPbrDiffraction diffraction;
};

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

public struct PrimeOpenPbrEval {
    public PrimeOpenPbrThroughput throughput;
    public float pdf;
};

public struct PrimeOpenPbrFloatPair {
    public float first;
    public float second;
};

public struct PrimeOpenPbrVecPair {
    public float3 first;
    public float3 second;
};

public struct PrimeOpenPbrRefractResult {
    public float3 wo;
    public float relativeIor;
    public uint valid;
};

public struct PrimeOpenPbrMicrofacet {
    public float2 alpha;
};

public struct PrimeOpenPbrSpecularFresnel {
    public float ior;
    public float energyIor;
    public float3 color;
};

public struct PrimeOpenPbrConductorFresnel {
    public float3 f0;
    // Preserves RoboCute's source field name. This is the relative Schlick tint at its 1/7
    // cosine anchor, not absolute F(82 degrees); adapters must solve it from a target reflectance.
    public float3 f82;
    public float3 f90;
    public float weight;
    public float3 energyF0;
};

public struct PrimeOpenPbrVolume {
    public float3 extinction;
    public float3 albedo;
    public float anisotropy;
    public float ior;
};

public struct PrimeOpenPbrVolumeStack {
    public PrimeOpenPbrVolume values[2];
    public uint count;
};

public struct PrimeOpenPbrSampleResult {
    public PrimeOpenPbrSample bsdfSample;
    public PrimeOpenPbrVolumeStack volumeStack;
    public float rayT;
};

public bool primeOpenPbrIsReflective(uint flags) {
    return (flags & PRIME_OPENPBR_FLAG_REFLECTION) != 0u;
}

public bool primeOpenPbrIsTransmissive(uint flags) {
    return (flags & PRIME_OPENPBR_FLAG_TRANSMISSION) != 0u;
}

public bool primeOpenPbrIsDiffuse(uint flags) {
    return (flags & PRIME_OPENPBR_FLAG_DIFFUSE) != 0u;
}

public bool primeOpenPbrIsSpecular(uint flags) {
    return (flags & PRIME_OPENPBR_FLAG_SPECULAR) != 0u;
}

public bool primeOpenPbrIsDelta(uint flags) {
    return (flags & PRIME_OPENPBR_FLAG_DELTA) != 0u;
}

public bool primeOpenPbrIsNonDelta(uint flags) {
    return (flags & (PRIME_OPENPBR_FLAG_DIFFUSE | PRIME_OPENPBR_FLAG_SPECULAR)) != 0u;
}

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

public PrimeOpenPbrThroughput primeOpenPbrThroughputAdd(PrimeOpenPbrThroughput a,
                                                        PrimeOpenPbrThroughput b) {
    PrimeOpenPbrThroughput result;
    result.value = a.value + b.value;
    result.flags = a.flags | b.flags;
    return result;
}

public PrimeOpenPbrThroughput primeOpenPbrThroughputScale(PrimeOpenPbrThroughput value,
                                                          float3 scale) {
    value.value *= scale;
    if (all(scale == float3(0.0))) {
        value.flags = PRIME_OPENPBR_FLAG_NONE;
    }
    return value;
}

public PrimeOpenPbrThroughput primeOpenPbrThroughputScale(PrimeOpenPbrThroughput value,
                                                          float scale) {
    value.value *= scale;
    if (scale == 0.0) {
        value.flags = PRIME_OPENPBR_FLAG_NONE;
    }
    return value;
}

public float primeOpenPbrSquare(float value) {
    return value * value;
}

public float2 primeOpenPbrSquare(float2 value) {
    return value * value;
}

public float3 primeOpenPbrSquare(float3 value) {
    return value * value;
}

public float primeOpenPbrPow4(float value) {
    float x = value * value;
    return x * x;
}

public float primeOpenPbrPow5(float value) {
    return primeOpenPbrPow4(value) * value;
}

public float primeOpenPbrPow6(float value) {
    float x = value * value * value;
    return x * x;
}

public float primeOpenPbrReduceSum(float2 value) {
    return value.x + value.y;
}

public float primeOpenPbrReduceSum(float3 value) {
    return value.x + value.y + value.z;
}

public float primeOpenPbrReduceMax(float2 value) {
    return max(value.x, value.y);
}

public float primeOpenPbrReduceMax(float3 value) {
    return max(value.x, max(value.y, value.z));
}

public float primeOpenPbrReduceMin(float3 value) {
    return min(value.x, min(value.y, value.z));
}

public float primeOpenPbrSpectrumToWeight(float3 value) {
    return primeOpenPbrReduceSum(value) / 3.0;
}

public PrimeOpenPbrOnb primeOpenPbrOnbFromNormal(float3 normal) {
    float signZ = normal.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0 / (signZ + normal.z);
    float b = normal.x * normal.y * a;
    PrimeOpenPbrOnb result;
    result.tangent = float3(1.0 + signZ * normal.x * normal.x * a, signZ * b, -signZ * normal.x);
    result.bitangent = float3(b, signZ + normal.y * normal.y * a, -normal.y);
    result.normal = normal;
    return result;
}

public float3 primeOpenPbrOnbToWorld(PrimeOpenPbrOnb onb, float3 value) {
    return value.x * onb.tangent + value.y * onb.bitangent + value.z * onb.normal;
}

public float3 primeOpenPbrOnbToLocal(PrimeOpenPbrOnb onb, float3 value) {
    return float3(dot(onb.tangent, value), dot(onb.bitangent, value), dot(onb.normal, value));
}

public float3 primeOpenPbrUniformSampleHemisphere(float2 sampleValue) {
    float sine = sqrt(1.0 - sampleValue.x * sampleValue.x);
    float phi = 2.0 * PRIME_OPENPBR_PI * sampleValue.y;
    return float3(sine * cos(phi), sine * sin(phi), sampleValue.x);
}

public float3 primeOpenPbrCosineSampleHemisphere(float2 sampleValue) {
    float radius = sqrt(sampleValue.x);
    float phi = 2.0 * PRIME_OPENPBR_PI * sampleValue.y;
    return float3(radius * cos(phi), radius * sin(phi), sqrt(1.0 - sampleValue.x));
}

public float primeOpenPbrF0ToIor(float f0) {
    float root = sqrt(clamp(f0, 0.0, 0.999));
    return (1.0 + root) / (1.0 - root);
}

public float3 primeOpenPbrF0ToIor(float3 f0) {
    float3 root = sqrt(clamp(f0, float3(0.0), float3(0.999)));
    return (float3(1.0) + root) / (float3(1.0) - root);
}

public float primeOpenPbrIorToF0(float ior) {
    return primeOpenPbrSquare((ior - 1.0) / (ior + 1.0));
}

public float2 primeOpenPbrSpecularNdfRoughnesses(float roughness, float anisotropy) {
    float2 alpha;
    if (anisotropy == 0.0) {
        return float2(primeOpenPbrSquare(roughness));
    }
    alpha.x = primeOpenPbrSquare(roughness) *
              sqrt(2.0 / (1.0 + primeOpenPbrSquare(1.0 - anisotropy)));
    alpha.y = (1.0 - anisotropy) * alpha.x;
    if (primeOpenPbrReduceMax(alpha) < 1.0e-4) {
        return float2(0.0);
    }
    return max(alpha, float2(1.0e-4));
}

public float primeOpenPbrIorAdjustment(float ior, float coatIor, float coatWeight) {
    if (coatWeight == 0.0) {
        return ior;
    }
    float boundary = coatIor > ior ? coatIor / ior : ior / coatIor;
    return lerp(ior, boundary, coatWeight);
}

public float primeOpenPbrIorAdjustment(float ior, float specularWeight) {
    float e = sign(ior - 1.0) * sqrt(clamp(specularWeight * primeOpenPbrIorToF0(ior), 0.0, 0.999));
    return (1.0 + e) / (1.0 - e);
}

public float primeOpenPbrThinDielectricRoughnessScaler2(float ior) {
    float eta = ior >= 1.0 ? ior : 1.0 / ior;
    return 3.7 * (eta - 1.0) * primeOpenPbrSquare(eta - 0.5) / (eta * eta * eta);
}

public PrimeOpenPbrVolume
primeOpenPbrVolumeFromTransmission(PrimeOpenPbrTransmission transmission) {
    PrimeOpenPbrVolume result;
    if (transmission.depth > 0.0) {
        float3 muT = -log(max(float3(1.0e-3), transmission.color)) / transmission.depth;
        float3 muS = transmission.scatter / transmission.depth;
        float3 muA = muT - muS;
        muA -= float3(min(0.0, primeOpenPbrReduceMin(muA)));
        result.extinction = muA + muS;
        result.albedo = float3(result.extinction.x != 0.0 ? muS.x / result.extinction.x : 0.0,
                               result.extinction.y != 0.0 ? muS.y / result.extinction.y : 0.0,
                               result.extinction.z != 0.0 ? muS.z / result.extinction.z : 0.0);
        result.anisotropy = clamp(transmission.scatterAnisotropy, -0.99, 0.99);
    } else {
        result.extinction = float3(0.0);
        result.albedo = float3(0.0);
        result.anisotropy = 0.0;
    }
    result.ior = 1.0;
    return result;
}

public bool primeOpenPbrStackPush(inout PrimeOpenPbrVolumeStack stack, PrimeOpenPbrVolume volume) {
    if (stack.count >= PRIME_OPENPBR_MAX_VOLUME_STACK_SIZE) {
        return false;
    }
    stack.values[stack.count] = volume;
    stack.count++;
    return true;
}

public bool primeOpenPbrStackPop(inout PrimeOpenPbrVolumeStack stack) {
    if (stack.count == 0u) {
        return false;
    }
    stack.count--;
    return true;
}
