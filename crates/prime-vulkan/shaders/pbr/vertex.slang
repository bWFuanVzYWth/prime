// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "pbr/vertex.slang";

import "bsdf/common/common.slang";
import "model/material/labpbr.slang";
import "model/material/defaults.slang";
import "model/material/optical.slang";
import "model/material/fresnel_code.slang";
import "model/material/translation.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/sample.slang";

public struct PrimePbrSource {
    // Linear Rec.2020 color and an already reconstructed world-space shading normal.
    public float3 baseColor;
    public float3 normal;
    // Normal atlas A contains derived distribution roughness. Optical G/B are canonical
    // categorical LabPBR bytes sampled from the current animation frame's base layer.
    public float4 normalSample;
    public float4 specularSample;
    public bool hasNormal;
    public bool hasSpecular;
    public bool thinWalled;
};

public struct PrimePbrSample {
    public BsdfSample bsdfSample;
    // Transport ABI: x = IOR; yzw = linear Rec.2020 extinction in inverse metres.
    public float4 medium;
};

public static const uint PRIME_PBR_VERTEX_THIN_WALLED = 1u << 16u;
public static const uint PRIME_PBR_VERTEX_DIELECTRIC = 1u << 17u;
public static const uint PRIME_PBR_VERTEX_HAS_NORMAL = 1u << 18u;
public static const uint PRIME_PBR_VERTEX_CONDUCTOR = 1u << 19u;

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
    return (vertex.control & PRIME_PBR_VERTEX_CONDUCTOR) != 0u;
}

// R/A are continuous filtered inputs; G/B retain their canonical point-selected identity.
// No frame, closure, source AO, emission alpha or tangent normal remains in this vertex.
public PrimePbrVertex primePbrVertex(float3 baseColor, float3 normal, float normalVariance,
                                     float3 opticalSample, bool hasNormal, bool hasSpecular,
                                     bool thinWalled, bool dielectric, float3 viewDirection) {
    PrimePbrVertex vertex;
    vertex.baseColor = baseColor;
    vertex.normal = dot(normal, viewDirection) < 0.0 ? -normal : normal;
    vertex.roughness = dielectric ? 0.0 : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    vertex.control = (hasNormal ? PRIME_PBR_VERTEX_HAS_NORMAL : 0u) |
                     (thinWalled ? PRIME_PBR_VERTEX_THIN_WALLED : 0u) |
                     (dielectric ? PRIME_PBR_VERTEX_DIELECTRIC : 0u);
    if (hasSpecular) {
        uint roughnessByte = uint(round(opticalSample.r * 255.0));
        vertex.roughness = clamp(1.0 - float(roughnessByte) / 255.0, 0.0, 1.0);
        if (!dielectric) {
            uint fresnelCode = uint(round(opticalSample.g * 255.0));
            vertex.control |= fresnelCode;
            if (primeIsSupportedConductorCode(fresnelCode)) {
                vertex.control |= PRIME_PBR_VERTEX_CONDUCTOR;
            } else {
                uint scattering = uint(round(opticalSample.b * 255.0));
                uint subsurface = scattering > 64u ? scattering - 64u : 0u;
                vertex.control |= subsurface << PRIME_OPTICAL_SUBSURFACE_SHIFT;
            }
        }
    }
    if (hasNormal)
        vertex.roughness = primeCombineLabPbrNormalRoughness(vertex.roughness,
                                                             clamp(normalVariance, 0.0, 1.0));
    return vertex;
}

public PrimePbrVertex primePbrVertex(PrimePbrSource source, float3 viewDirection, bool dielectric) {
    return primePbrVertex(source.baseColor, source.normal, source.normalSample.a,
                          source.specularSample.rgb, source.hasNormal, source.hasSpecular,
                          source.thinWalled, dielectric, viewDirection);
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

public PrimeOpenPbrOnb primePbrVertexFrame(PrimePbrVertex vertex, float3 viewDirection,
                                           float4 incidentMedium, float4 transmittedMedium) {
    PrimeOpenPbrOnb onb = primeOpenPbrOnbFromNormal(vertex.normal);
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

// Thick authored SSS was not a supported legacy full-model compact topology.
// Retain the current source approximation on that explicit extension instead
// of dropping its diffuse-transmission event or claiming reference equivalence.
public bool primePbrVertexUsesLegacyThickSss(PrimePbrVertex vertex) {
    return !primePbrVertexIsDielectric(vertex) && !primePbrVertexIsConductor(vertex) &&
           !primePbrVertexIsThinWalled(vertex) &&
           ((vertex.control >> PRIME_OPTICAL_SUBSURFACE_SHIFT) & 0xffu) != 0u;
}
