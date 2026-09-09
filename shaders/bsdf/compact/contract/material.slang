// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/contract/material.slang";

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

public struct PrimeOpenPbrEmission { public float3 luminance; };
public struct PrimeOpenPbrBase { public float3 color; };

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
