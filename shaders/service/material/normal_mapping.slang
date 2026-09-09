// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/material/normal_mapping.slang";

import "contract/material/normal.slang";

public float3 primeEnsureValidNormalMappedReflection(
        float3 outwardGeometricNormal,
        float3 viewDirection,
        float3 outwardShadingNormal)
{
    bool frontFacing = dot(outwardGeometricNormal, viewDirection) >= 0.0;
    float3 geometricNormal = frontFacing
            ? outwardGeometricNormal
            : -outwardGeometricNormal;
    float3 outwardNormal = dot(outwardShadingNormal, outwardGeometricNormal) >= 0.0
            ? outwardShadingNormal
            : -outwardShadingNormal;
    float3 shadingNormal = frontFacing ? outwardNormal : -outwardNormal;

    float geometricViewCosine = max(dot(viewDirection, geometricNormal), 0.0);
    float minimumReflectionCosine = min(0.9 * geometricViewCosine, 0.01);
    float3 reflected = 2.0 * dot(shadingNormal, viewDirection) * shadingNormal
            - viewDirection;
    if (dot(geometricNormal, reflected) >= minimumReflectionCosine) {
        return outwardNormal;
    }

    // Cycles' valid-specular-reflection construction rotates only the normal-map slope that
    // causes the invalid reflection. The common path above remains unchanged.
    float3 tangent = shadingNormal
            - dot(shadingNormal, geometricNormal) * geometricNormal;
    float tangentLengthSquared = dot(tangent, tangent);
    if (tangentLengthSquared <= 1.0e-12) {
        return outwardGeometricNormal;
    }
    tangent *= rsqrt(tangentLengthSquared);
    float tangentViewCosine = dot(viewDirection, tangent);
    float a = tangentViewCosine * tangentViewCosine
            + geometricViewCosine * geometricViewCosine;
    float b = 2.0 * (a
            + geometricViewCosine * minimumReflectionCosine);
    float c = (minimumReflectionCosine + geometricViewCosine)
            * (minimumReflectionCosine + geometricViewCosine);
    float discriminant = max(b * b - 4.0 * a * c, 0.0);
    float root = sqrt(discriminant);
    float correctedTangentCosineSquared;
    float correctedGeometricCosineSquared;
    if (tangentViewCosine < 0.0) {
        // This root approaches Nz^2=1 at extreme grazing angles. Solve the equivalent
        // polynomial for Nx^2=1-Nz^2 so f32 does not erase the remaining tangent slope.
        float tangentC = (geometricViewCosine - minimumReflectionCosine)
                * (geometricViewCosine - minimumReflectionCosine);
        float tangentB = -4.0 * (a
                - geometricViewCosine * minimumReflectionCosine);
        float tangentDiscriminant = max(
                tangentB * tangentB - 16.0 * a * tangentC,
                0.0);
        correctedTangentCosineSquared = 2.0 * tangentC
                / max(-tangentB + sqrt(tangentDiscriminant), 1.0e-12);
        correctedTangentCosineSquared = clamp(
                correctedTangentCosineSquared, 0.0, 1.0);
        correctedGeometricCosineSquared = max(
                1.0 - correctedTangentCosineSquared, 0.0);
    } else {
        // c / (b + root) is algebraically identical to the smaller quadratic root,
        // without subtracting two nearly equal f32 values.
        correctedGeometricCosineSquared = clamp(
                c / max(b + root, 1.0e-12), 0.0, 1.0);
        correctedTangentCosineSquared = max(
                1.0 - correctedGeometricCosineSquared, 0.0);
    }
    float correctedTangentCosine = sqrt(correctedTangentCosineSquared);
    float correctedGeometricCosine = sqrt(correctedGeometricCosineSquared);
    float3 corrected = correctedTangentCosine * tangent
            + correctedGeometricCosine * geometricNormal;
    return frontFacing ? corrected : -corrected;
}

public float3 primeNormalMappedShadingNormalForView(
        uint opticalControl,
        float3 outwardGeometricNormal,
        float3 viewDirection,
        float3 outwardShadingNormal)
{
    return primeMaterialUsesNormalMap(opticalControl)
            ? primeEnsureValidNormalMappedReflection(
                    outwardGeometricNormal,
                    viewDirection,
                    outwardShadingNormal)
            : outwardShadingNormal;
}
