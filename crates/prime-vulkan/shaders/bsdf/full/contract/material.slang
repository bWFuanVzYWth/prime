// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/contract/material.slang";

public struct PrimeFullOpenPbrOnb {
    public float3 tangent;
    public float3 bitangent;
    public float3 normal;
};

public struct PrimeFullOpenPbrWeight {
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

public struct PrimeFullOpenPbrGeometry {
    public uint thinWalled;
    public float thickness;
    public PrimeFullOpenPbrOnb onb;
};

public struct PrimeFullOpenPbrSpecular {
    public float3 color;
    public float roughness;
    public float roughnessAnisotropy;
    public float ior;
};

public struct PrimeFullOpenPbrEmission {
    public float3 luminance;
};
public struct PrimeFullOpenPbrBase {
    public float3 color;
};

public struct PrimeFullOpenPbrSubsurface {
    public float3 color;
    public float3 radius;
    public float scatterAnisotropy;
};

public struct PrimeFullOpenPbrTransmission {
    public float3 color;
    public float depth;
    public float3 scatter;
    public float scatterAnisotropy;
    public float dispersionScale;
    public float dispersionAbbeNumber;
};

public struct PrimeFullOpenPbrCoat {
    public float3 color;
    public float roughness;
    public float roughnessAnisotropy;
    public float ior;
    public float darkening;
    public float roughening;
    public PrimeFullOpenPbrOnb onb;
};

public struct PrimeFullOpenPbrFuzz {
    public float3 color;
    public float roughness;
};

public struct PrimeFullOpenPbrThinFilm {
    public float thickness;
    public float ior;
};

public struct PrimeFullOpenPbrDiffraction {
    public float3 color;
    public float thickness;
    public float2 invPitch;
    public float angle;
    public uint lobeCount;
    public uint kind;
};

public struct PrimeFullOpenPbrMaterial {
    public PrimeFullOpenPbrWeight weight;
    public PrimeFullOpenPbrGeometry geometry;
    public PrimeFullOpenPbrSpecular specular;
    public PrimeFullOpenPbrEmission emission;
    public PrimeFullOpenPbrBase base;
    public PrimeFullOpenPbrSubsurface subsurface;
    public PrimeFullOpenPbrTransmission transmission;
    public PrimeFullOpenPbrCoat coat;
    public PrimeFullOpenPbrFuzz fuzz;
    public PrimeFullOpenPbrThinFilm thinFilm;
    public PrimeFullOpenPbrDiffraction diffraction;
};
