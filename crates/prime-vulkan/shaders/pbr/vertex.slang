// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "pbr/vertex.slang";

import "bsdf/full/contract/material.slang";
import "bsdf/full/math/frame.slang";
__exported import "model/material/canonical.slang";
import "service/bsdf/contract/sample.slang";

public struct PrimePbrSample {
    public BsdfSample bsdfSample;
    // Transport ABI: x = IOR; yzw = linear Rec.2020 extinction in inverse metres.
    public float4 medium;
};

public static const uint PRIME_PBR_VERTEX_THIN_WALLED = 1u << 24u;
public static const uint PRIME_PBR_VERTEX_DIELECTRIC = 1u << 25u;
public static const uint PRIME_PBR_VERTEX_HAS_NORMAL = 1u << 26u;

public struct PrimePbrVertex {
    public float3 baseColor;
    public float3 normal;
    public float roughness;
    public uint control;
};

public bool primePbrVertexHasNormal(PrimePbrVertex vertex) {
    return (vertex.control & PRIME_PBR_VERTEX_HAS_NORMAL) != 0u;
}

public bool primePbrVertexIsDielectric(PrimePbrVertex vertex) {
    return (vertex.control & PRIME_PBR_VERTEX_DIELECTRIC) != 0u;
}

public bool primePbrVertexIsThinWalled(PrimePbrVertex vertex) {
    return (vertex.control & PRIME_PBR_VERTEX_THIN_WALLED) != 0u;
}

public bool primePbrVertexIsConductor(PrimePbrVertex vertex) {
    return (vertex.control & 255u) == 255u;
}
public float primePbrVertexMetalness(PrimePbrVertex vertex) {
    return float(vertex.control & 255u) / 255.0;
}
public float primePbrVertexSubsurface(PrimePbrVertex vertex) {
    return float((vertex.control >> 8u) & 255u) / 255.0;
}
public float primePbrVertexIor(PrimePbrVertex vertex) {
    return primeDecodeMaterialIor((vertex.control >> 16u) & 255u);
}

// M/S are quantized once for the compact query-crossing control word. IOR already came
// from current-frame mip0; roughness consumes the filtered MERS B without another byte round.
public PrimePbrVertex primePbrVertex(float3 baseColor, float3 normal, float normalVariance,
                                     float4 mersSample, float iorSample, bool hasNormal,
                                     bool hasMers, bool thinWalled, bool dielectric,
                                     float3 viewDirection) {
    PrimePbrVertex vertex;
    vertex.baseColor = baseColor;
    vertex.normal = dot(normal, viewDirection) < 0.0 ? -normal : normal;
    vertex.roughness = dielectric ? 0.0 : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    vertex.control = (hasNormal ? PRIME_PBR_VERTEX_HAS_NORMAL : 0u) |
                     (thinWalled ? PRIME_PBR_VERTEX_THIN_WALLED : 0u) |
                     (dielectric ? PRIME_PBR_VERTEX_DIELECTRIC : 0u);
    // Missing specular on a CPU-declared plant preset uses a colored thin SSS fallback.
    // Authored specular (including an explicit zero SSS channel) always takes precedence.
    vertex.control |= uint(round(saturate(iorSample) * 255.0)) << 16u;
    if (!hasMers && thinWalled && !dielectric)
        vertex.control |= 128u << 8u;
    if (hasMers) {
        vertex.roughness = saturate(mersSample.b);
        if (!dielectric) {
            uint metalness = uint(round(saturate(mersSample.r) * 255.0));
            vertex.control |= metalness;
            if (thinWalled && metalness < 255u)
                vertex.control |= uint(round(saturate(mersSample.a) * 255.0)) << 8u;
        }
    }
    if (hasNormal)
        vertex.roughness =
                primeCombineNormalRoughness(vertex.roughness, clamp(normalVariance, 0.0, 1.0));
    return vertex;
}

public float3 primePbrVertexFrameNormal(PrimePbrVertex vertex, float3 viewDirection,
                                        float4 incidentMedium, float4 transmittedMedium) {
    float3 normal = vertex.normal;
    if (primePbrVertexIsDielectric(vertex)) {
        bool exiting =
                !primePbrVertexIsThinWalled(vertex) && incidentMedium.x > transmittedMedium.x;
        if ((dot(normal, viewDirection) >= 0.0) == exiting)
            normal = -normal;
    }
    return normal;
}

public PrimeFullOpenPbrOnb primePbrVertexFrame(PrimePbrVertex vertex, float3 viewDirection,
                                               float4 incidentMedium, float4 transmittedMedium) {
    PrimeFullOpenPbrOnb onb = primeFullOpenPbrOnbFromNormal(vertex.normal);
    if (primePbrVertexIsDielectric(vertex)) {
        bool exiting =
                !primePbrVertexIsThinWalled(vertex) && incidentMedium.x > transmittedMedium.x;
        bool facesView = dot(onb.normal, viewDirection) >= 0.0;
        if (facesView == exiting) {
            onb.normal = -onb.normal;
            onb.bitangent = -onb.bitangent;
        }
    }
    return onb;
}

public float primePbrVertexInterfaceIor(PrimePbrVertex vertex, float4 incidentMedium,
                                        float4 transmittedMedium) {
    bool exiting = !primePbrVertexIsThinWalled(vertex) && incidentMedium.x > transmittedMedium.x;
    float materialIor = exiting ? incidentMedium.x : transmittedMedium.x;
    float inverseOutsideIor = 1.0 / (exiting ? transmittedMedium.x : incidentMedium.x);
    return materialIor * inverseOutsideIor;
}
