// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/reconstruct/nrd/sh.slang";

import "service/reconstruct/nrd/common.slang";

// Application-side half of NRD 4.17.4's REBLUR SH contract. Keep these operations synchronized
// with NRD/Shaders/NRD.hlsli when the bundled native version changes.
static const float PRIME_NRD_PI = 3.14159265358979323846;
static const float PRIME_NRD_EPS = 1.0e-6;
static const float PRIME_NRD_REJITTER_VIEW_Z_THRESHOLD = 0.01;
static const float PRIME_NRD_REJITTER_AMPLITUDE = 2.0;

public struct PrimeNrdSg {
    public float c0;
    public float2 chroma;
    public float normalizedHitDistance;
    public float3 c1;
};

public struct PrimeNrdResolvedSg {
    public float3 diffuse;
    public float3 specular;
};

public PrimeNrdSg primeNrdUnpackSg(float4 sh0, float3 sh1) {
    PrimeNrdSg sg;
    sg.c0 = primeNrdIsFinite(sh0.x) ? max(sh0.x, 0.0) : 0.0;
    sg.chroma = primeNrdIsFinite(sh0.yz) ? sh0.yz : float2(0.0);
    sg.normalizedHitDistance = primeNrdSanitizeUnit(sh0.w, 0.0);
    sg.c1 = primeNrdIsFinite(sh1) ? sh1 : float3(0.0);
    return sg;
}
public float3 primeNrdSgExtractColor(PrimeNrdSg sg) {
    return primeNrdYCoCgToLinear(float3(sg.c0, sg.chroma));
}

float3 primeNrdSgExtractDirection(PrimeNrdSg sg) {
    return sg.c1 / max(length(sg.c1), PRIME_NRD_EPS);
}

float3 primeNrdYCoCgToLinearCorrected(float y, float y0, float2 chroma) {
    y = max(y, 0.0);
    chroma *= (y + PRIME_NRD_EPS) / (y0 + PRIME_NRD_EPS);
    return primeNrdYCoCgToLinear(float3(y, chroma));
}

float primeNrdPow5(float value) {
    return pow(clamp(1.0 - value, 0.0, 1.0), 5.0);
}

float primeNrdDistributionTerm(float roughness, float noH) {
    float m = roughness * roughness;
    float m2 = m * m;
    float t = (noH * m2 - noH) * noH + 1.0;
    float a = m / t;
    return a * a / PRIME_NRD_PI;
}

float primeNrdGeometryTerm(float roughness, float noL, float noV) {
    float m = roughness * roughness;
    float m2 = m * m;
    float a = noV * sqrt((noL - m2 * noL) * noL + m2);
    float b = noL * sqrt((noV - m2 * noV) * noV + m2);
    return 0.5 / (a + b);
}

float primeNrdDiffuseTerm(float roughness, float noL, float noV, float voH) {
    float f = 2.0 * voH * voH * roughness - 0.5;
    float fdV = f * primeNrdPow5(noV) + 1.0;
    float fdL = f * primeNrdPow5(noL) + 1.0;
    return fdV * fdL / PRIME_NRD_PI;
}

float primeNrdSgInnerProduct(
        float firstC0,
        float3 firstDirection,
        float firstSharpness,
        float secondC0,
        float3 secondDirection,
        float secondSharpness) {
    float3 direction = firstSharpness * firstDirection
            + secondSharpness * secondDirection;
    float directionLength = length(direction);
    float value = exp(directionLength - firstSharpness - secondSharpness);
    value *= 1.0 - exp(-2.0 * directionLength);
    value /= max(directionLength, PRIME_NRD_EPS);
    return 2.0 * PRIME_NRD_PI * value * firstC0 * secondC0;
}

public float3 primeNrdResolveSgDiffuse(
        PrimeNrdSg sg, float3 normal, float3 view, float roughness) {
    float3 lightDirection = primeNrdSgExtractDirection(sg);
    float noL = clamp(dot(normal, lightDirection), 0.0, 1.0);
    float y = primeNrdSgInnerProduct(
            1.0,
            normal,
            2.0,
            sg.c0 * 2.0,
            lightDirection,
            2.0);
    float3 halfDirection = normalize(lightDirection + view);
    float noV = abs(dot(normal, view));
    float voH = abs(dot(view, halfDirection));
    y *= primeNrdDiffuseTerm(roughness, noL, noV, voH);
    y *= lerp(1.0, lerp(1.5, 0.6, roughness), primeNrdPow5(noV));
    y = max(y, sg.c0 / PRIME_NRD_PI);
    float3 resolved = primeNrdYCoCgToLinearCorrected(y, sg.c0, sg.chroma);
    return primeNrdIsFinite(resolved) ? resolved : primeNrdSgExtractColor(sg);
}

public float3 primeNrdResolveSgSpecular(
        PrimeNrdSg sg, float3 normal, float3 view, float roughness) {
    roughness = max(roughness, 0.05);
    float m = roughness * roughness;
    float m2 = m * m;
    float3 lightDirection = primeNrdSgExtractDirection(sg);
    float noL = clamp(dot(normal, lightDirection), 0.0, 1.0);
    float3 halfDirection = normalize(lightDirection + view);
    float noV = abs(dot(normal, view));
    float voH = abs(dot(view, halfDirection));
    noV = lerp(0.02, 1.0, noV);
    float lightSharpness = 2.0 / m2;
    float ndfSharpness = 0.5 / max(m2 * voH, 1.0e-8);
    float y = primeNrdSgInnerProduct(
            1.0,
            lightDirection,
            ndfSharpness,
            sg.c0 * lightSharpness,
            lightDirection,
            lightSharpness);
    y *= primeNrdGeometryTerm(roughness, noL, noV) * noL;
    y *= lerp(lerp(0.1, 0.4, m2), 0.8, noV);
    y = max(y, sg.c0 / PRIME_NRD_PI);
    float3 resolved = primeNrdYCoCgToLinearCorrected(y, sg.c0, sg.chroma);
    return primeNrdIsFinite(resolved) ? resolved : primeNrdSgExtractColor(sg);
}

float2 primeNrdComputeBrdfs(
        float3 diffuseLight,
        float3 specularLight,
        float3 normal,
        float3 view,
        float roughness) {
    float noV = abs(dot(normal, view));
    float3 diffuseHalf = normalize(diffuseLight + view);
    float diffuseNoL = clamp(dot(normal, diffuseLight), 0.0, 1.0);
    float diffuseVoH = abs(dot(view, diffuseHalf));
    float diffuse = primeNrdDiffuseTerm(
            roughness, diffuseNoL, noV, diffuseVoH) * diffuseNoL;

    float3 specularHalf = normalize(specularLight + view);
    float specularNoL = clamp(dot(normal, specularLight), 0.0, 1.0);
    float specularNoH = clamp(dot(normal, specularHalf), 0.0, 1.0);
    float specular = primeNrdDistributionTerm(roughness, specularNoH)
            * primeNrdGeometryTerm(roughness, specularNoL, noV)
            * specularNoL;
    return float2(diffuse, specular);
}

public float2 primeNrdSgReJitter(
        PrimeNrdSg diffuseSg,
        PrimeNrdSg specularSg,
        float3 view,
        float roughness,
        float viewZ,
        float4 neighborViewZ,
        float3 normal,
        float3 eastNormal,
        float3 westNormal,
        float3 northNormal,
        float3 southNormal) {
    float noV = abs(dot(normal, view));
    float threshold = PRIME_NRD_REJITTER_VIEW_Z_THRESHOLD * abs(viewZ)
            / (noV * 0.95 + 0.05);
    bool4 withinSurface = lessThanEqual(abs(neighborViewZ - viewZ), float4(threshold));
    float4 neighborAlignment = float4(
            dot(normal, eastNormal),
            dot(normal, westNormal),
            dot(normal, northNormal),
            dot(normal, southNormal));
    bool symmetrical = all(withinSurface)
            && all(greaterThan(neighborAlignment, float4(PRIME_NRD_EPS)));
    if (!symmetrical) {
        return float2(1.0);
    }

    float3 diffuseLight = primeNrdSgExtractDirection(diffuseSg);
    float3 specularLight = normalize(lerp(view, primeNrdSgExtractDirection(specularSg), roughness));
    float2 center = primeNrdComputeBrdfs(
            diffuseLight, specularLight, normal, view, roughness);
    float2 average = primeNrdComputeBrdfs(
            diffuseLight, specularLight, eastNormal, view, roughness);
    average += primeNrdComputeBrdfs(
            diffuseLight, specularLight, northNormal, view, roughness);
    average += primeNrdComputeBrdfs(
            diffuseLight, specularLight, westNormal, view, roughness);
    average += primeNrdComputeBrdfs(
            diffuseLight, specularLight, southNormal, view, roughness);
    average *= 0.25;
    float2 scale = (center + PRIME_NRD_EPS) / (average + PRIME_NRD_EPS);
    scale = clamp(
            scale,
            float2(1.0 / PRIME_NRD_REJITTER_AMPLITUDE),
            float2(PRIME_NRD_REJITTER_AMPLITUDE));
    return primeNrdIsFinite(scale) ? scale : float2(1.0);
}

public PrimeNrdResolvedSg primeNrdResolveFilteredSg(
        PrimeNrdSg diffuseSg,
        PrimeNrdSg specularSg,
        float3 view,
        float roughness,
        float viewZ,
        float4 neighborViewZ,
        float3 normal,
        float3 eastNormal,
        float3 westNormal,
        float3 northNormal,
        float3 southNormal) {
    bool hasDiffuseDirection =
            dot(diffuseSg.c1, diffuseSg.c1) > PRIME_NRD_EPS * PRIME_NRD_EPS;
    bool hasSpecularDirection =
            dot(specularSg.c1, specularSg.c1) > PRIME_NRD_EPS * PRIME_NRD_EPS;
    PrimeNrdResolvedSg resolved;
    resolved.diffuse = !hasDiffuseDirection
            ? primeNrdSgExtractColor(diffuseSg)
            : primeNrdResolveSgDiffuse(diffuseSg, normal, view, roughness);
    resolved.specular = !hasSpecularDirection
            ? primeNrdSgExtractColor(specularSg)
            : primeNrdResolveSgSpecular(specularSg, normal, view, roughness);
    if (!hasDiffuseDirection && !hasSpecularDirection) {
        return resolved;
    }

    float2 scale = primeNrdSgReJitter(
            diffuseSg,
            specularSg,
            view,
            roughness,
            viewZ,
            neighborViewZ,
            normal,
            eastNormal,
            westNormal,
            northNormal,
            southNormal);
    if (!hasDiffuseDirection) scale.x = 1.0;
    if (!hasSpecularDirection) scale.y = 1.0;
    resolved.diffuse *= scale.x;
    resolved.specular *= scale.y;
    return resolved;
}
