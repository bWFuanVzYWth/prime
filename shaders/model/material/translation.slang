// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/material/translation.slang";

import "model/material/defaults.slang";
import "math/color_space.slang";

public struct PrimeOpticalMaterial
{
    public float perceptualRoughness;
    public float dielectricF0;
    public float subsurfaceWeight;
    public uint fresnelCode;
    public uint thinWalled;
};

public bool primeIsStandardConductorCode(uint fresnelCode)
{
    return fresnelCode >= PRIME_FRESNEL_IRON && fresnelCode <= PRIME_FRESNEL_SILVER;
}

public bool primeIsCustomConductorCode(uint fresnelCode)
{
    return fresnelCode == PRIME_FRESNEL_CUSTOM_CONDUCTOR;
}

public bool primeIsSupportedConductorCode(uint fresnelCode)
{
    return primeIsStandardConductorCode(fresnelCode)
            || primeIsCustomConductorCode(fresnelCode);
}

public PrimeOpticalMaterial primeDecodeOpticalMaterial(
        float roughness,
        uint opticalControl,
        uint materialControl)
{
    uint fresnelCode = opticalControl & PRIME_OPTICAL_FRESNEL_MASK;
    uint subsurfaceCode = opticalControl >> PRIME_OPTICAL_SUBSURFACE_SHIFT & 0xff;
    bool transmissive = primeMaterialIsTransmissive(materialControl);
    PrimeOpticalMaterial result;
    result.perceptualRoughness = clamp(roughness, 0.0, 1.0);
    result.fresnelCode = !transmissive
                    && !primeMaterialIsColorlessGlass(materialControl)
                    && primeIsSupportedConductorCode(fresnelCode)
            ? fresnelCode
            : PRIME_FRESNEL_DEFAULT_DIELECTRIC;
    result.dielectricF0 = fresnelCode <= 230
            ? primeDecodeDielectricF0(fresnelCode)
            : PRIME_DEFAULT_DIELECTRIC_F0;
    result.thinWalled = (materialControl & PRIME_MATERIAL_THIN_WALLED) != 0 ? 1 : 0;
    // LabPBR has no usable thick-surface SSS topology in Prime. Only an explicitly thin
    // material may carry this weight; thick input is intentionally translated to diffuse.
    result.subsurfaceWeight = result.thinWalled != 0
            ? float(min(subsurfaceCode, 190)) / 190.0
            : 0.0;
    return result;
}

public bool primeOpticalMaterialIsMetal(PrimeOpticalMaterial material)
{
    return primeFresnelIsConductor(material.fresnelCode);
}

public bool primeAirGapCompatible(
        bool enabled,
        uint currentControl,
        uint currentFresnelCode,
        uint adjacentControl,
        uint adjacentFresnelCode)
{
    return enabled
            && primeMaterialIsTransmissive(currentControl)
            && primeMaterialIsTransmissive(adjacentControl)
            && !primeMaterialIsWater(currentControl)
            && !primeMaterialIsWater(adjacentControl)
            && (currentControl
                    & (PRIME_MATERIAL_THIN_WALLED
                            | PRIME_MATERIAL_DECORATIVE_INTERFACE)) == 0
            && (adjacentControl
                    & (PRIME_MATERIAL_THIN_WALLED
                            | PRIME_MATERIAL_DECORATIVE_INTERFACE)) == 0
            && currentFresnelCode == adjacentFresnelCode;
}

public float3 primeConductorReflectance(float cosineIncident, float3 eta, float3 k)
{
    float cosine = abs(cosineIncident);
    float cosine2 = cosine * cosine;
    float sine2 = 1.0 - cosine2;
    float3 eta2 = eta * eta;
    float3 k2 = k * k;
    float3 t0 = eta2 - k2 - float3(sine2);
    float3 a2PlusB2 = sqrt(t0 * t0 + 4.0 * eta2 * k2);
    float3 a2 = float3(
            t0.x >= 0.0
                    ? 0.5 * (a2PlusB2.x + t0.x)
                    : 2.0 * eta2.x * k2.x / (a2PlusB2.x - t0.x),
            t0.y >= 0.0
                    ? 0.5 * (a2PlusB2.y + t0.y)
                    : 2.0 * eta2.y * k2.y / (a2PlusB2.y - t0.y),
            t0.z >= 0.0
                    ? 0.5 * (a2PlusB2.z + t0.z)
                    : 2.0 * eta2.z * k2.z / (a2PlusB2.z - t0.z));
    float3 a = sqrt(a2);
    float3 t1 = a2PlusB2 + float3(cosine2);
    float3 t2 = 2.0 * cosine * a;
    float3 rs = (t1 - t2) / (t1 + t2);
    float3 t3 = cosine2 * a2PlusB2 + float3(sine2 * sine2);
    float3 t4 = t2 * sine2;
    float3 rp = rs * (t3 - t4) / (t3 + t4);
    return clamp(0.5 * (rs + rp), float3(0.0), float3(1.0));
}

public bool primeConductorOpticalConstants(uint fresnelCode, out float3 eta, out float3 k)
{
    if (fresnelCode == PRIME_FRESNEL_IRON) {
        eta = float3(2.9114, 2.9497, 2.5845);
        k = float3(3.0893, 2.9318, 2.7670);
    } else if (fresnelCode == PRIME_FRESNEL_GOLD) {
        eta = float3(0.18299, 0.42108, 1.3734);
        k = float3(3.4242, 2.3459, 1.7704);
    } else if (fresnelCode == PRIME_FRESNEL_ALUMINIUM) {
        eta = float3(1.3456, 0.96521, 0.61722);
        k = float3(7.4746, 6.3995, 5.3031);
    } else if (fresnelCode == PRIME_FRESNEL_CHROME) {
        eta = float3(3.1071, 3.1812, 2.3230);
        k = float3(3.3314, 3.3291, 3.1350);
    } else if (fresnelCode == PRIME_FRESNEL_COPPER) {
        eta = float3(0.27105, 0.67693, 1.3164);
        k = float3(3.6092, 2.6248, 2.2921);
    } else if (fresnelCode == PRIME_FRESNEL_LEAD) {
        eta = float3(1.9100, 1.8300, 1.4400);
        k = float3(3.5100, 3.4000, 3.1800);
    } else if (fresnelCode == PRIME_FRESNEL_PLATINUM) {
        eta = float3(2.3757, 2.0847, 1.8453);
        k = float3(4.2655, 3.7153, 3.1365);
    } else if (fresnelCode == PRIME_FRESNEL_SILVER) {
        eta = float3(0.15943, 0.14512, 0.13547);
        k = float3(3.9291, 3.1900, 2.3808);
    } else {
        eta = float3(0.0);
        k = float3(0.0);
        return false;
    }
    return true;
}

public struct PrimeConductorFresnel
{
    public float3 f0;
    public float3 f82Tint;
};

public PrimeConductorFresnel primeConductorFresnel(float3 baseColor, uint fresnelCode)
{
    PrimeConductorFresnel result;
    float3 sourceTint = clamp(
            primeLinearRec2020ToLinearBt709(baseColor), 0.0, 1.0);
    float3 eta;
    float3 k;
    const float f82AnchorCosine = 1.0 / 7.0;
    float schlick82Weight = pow(1.0 - f82AnchorCosine, 5.0);
    if (primeConductorOpticalConstants(fresnelCode, eta, k)) {
        float3 sourceF0 = primeConductorReflectance(1.0, eta, k) * sourceTint;
        float3 sourceF82 = primeConductorReflectance(
                f82AnchorCosine, eta, k) * sourceTint;
        result.f0 = clamp(primeLinearSrgbToLinearRec2020(sourceF0), 0.0, 1.0);
        float3 targetF82 = clamp(
                primeLinearSrgbToLinearRec2020(sourceF82), 0.0, 1.0);
        float3 untintedSchlickF82 = lerp(result.f0, float3(1.0), schlick82Weight);
        result.f82Tint = clamp(targetF82 / untintedSchlickF82, 0.0, 1.0);
    } else if (primeIsCustomConductorCode(fresnelCode)) {
        result.f0 = clamp(baseColor, 0.0, 1.0);
        result.f82Tint = float3(1.0);
    } else {
        result.f0 = float3(PRIME_DEFAULT_DIELECTRIC_F0);
        result.f82Tint = float3(1.0);
    }
    return result;
}
