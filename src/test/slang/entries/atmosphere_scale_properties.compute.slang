// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.


import "model/atmosphere/sky.slang";
import "model/atmosphere/spectrum.slang";




[[vk::binding(0, 0)]] StructuredBuffer<float4> atmosphereScaleInput;

[[vk::binding(1, 0)]] RWStructuredBuffer<float4> atmosphereScaleOutput;

struct AtmosphereScalePush {
    uint count;
};
[[vk::push_constant]] ConstantBuffer<AtmosphereScalePush> atmosphereScalePush;

AtmCoefficients referenceCoefficients(int group, float altitudeKm) {
    float altitude = max(altitudeKm, 0.0);
    float4 aerosolScattering = float4(0.0);
    float4 aerosolAbsorption = float4(0.0);
    float falloff = exp(-altitude / 8.0);
    float backgroundBlend = smoothstep(1.0, 2.0, altitude);
    for (int species = 0; species < 4; ++species) {
        float density = lerp(
                atmAerosolBaseDensity(species) * falloff,
                atmAerosolBackgroundDensity(species) * falloff,
                backgroundBlend);
        aerosolScattering +=
                atmAerosolScatteringCrossSection(group, species)
                        * density;
        aerosolAbsorption +=
                atmAerosolAbsorptionCrossSection(group, species)
                        * density;
    }
    float4 molecularScattering = atmMolecularScattering(group)
            * exp(-0.07771971 * pow(altitude, 1.16364243));
    float ozoneHeight = altitude + 1.0e-4;
    float ozoneT = log(ozoneHeight) - 3.22261;
    float ozoneDensity = 3.78547397e20 / ozoneHeight
            * exp(-ozoneT * ozoneT * 5.55555555);
    float4 molecularAbsorption =
            atmOzoneCrossSection(group) * 381.0 * ozoneDensity;

    AtmCoefficients result;
    result.aerosolScattering = aerosolScattering;
    result.molecularScattering = molecularScattering;
    result.extinction = aerosolScattering + aerosolAbsorption
            + molecularScattering + molecularAbsorption;
    return result;
}

float relativeError(float4 actual, float4 expected) {
    float4 error = abs(actual - expected) / max(abs(expected), float4(1.0e-20));
    return max(max(error.x, error.y), max(error.z, error.w));
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID, uint3 groupThreadId : SV_GroupThreadID, uint3 groupId : SV_GroupID, uint groupIndex : SV_GroupIndex) {
    uint index = dispatchThreadId.x;
    if (index >= atmosphereScalePush.count) {
        return;
    }
    float4 value = atmosphereScaleInput[index];
    float altitude = value.x;
    int group = int(value.y);
    float mu = clamp(value.z, -1.0, 1.0);
    float worldDistance = max(value.w, 0.0);
    float3 direction = float3(sqrt(max(1.0 - mu * mu, 0.0)), mu, 0.0);

    AtmCoefficients actual =
            atmCoefficients(group, altitude);
    AtmCoefficients expected =
            referenceCoefficients(group, altitude);
    float topDistance = atmRaySphere(
            float3(
                    0.0,
                    ATM_BOTTOM_RADIUS_KM + altitude,
                    0.0),
            direction,
            ATM_TOP_RADIUS_KM);
    float referenceTopDistance = atmRaySphere(
            float3(0.0, 6360.0 + altitude, 0.0),
            direction,
            6460.0);
    float geometryError = abs(topDistance - referenceTopDistance)
            / max(abs(referenceTopDistance), 1.0e-5);
    float horizonRadius = ATM_BOTTOM_RADIUS_KM + altitude
            - ATM_PLANET_RADIUS_OFFSET_KM;
    float horizonDiscriminant = horizonRadius * horizonRadius * mu * mu
            - (horizonRadius * horizonRadius
                    - ATM_BOTTOM_RADIUS_KM * ATM_BOTTOM_RADIUS_KM);
    bool expectedDistantVisible = !(mu < 0.0 && horizonDiscriminant >= 0.0);
    float horizonError = altitude > ATM_PLANET_RADIUS_OFFSET_KM
            && atmDistantDirectionVisible(
                            ATM_BOTTOM_RADIUS_KM + altitude, direction)
                    != expectedDistantVisible
            ? 1.0
            : 0.0;
    geometryError = max(geometryError, horizonError);
    float modelError = max(
            relativeError(
                    actual.aerosolScattering,
                    expected.aerosolScattering),
            max(
                    relativeError(
                            actual.molecularScattering,
                            expected.molecularScattering),
                    relativeError(
                            actual.extinction,
                            expected.extinction)));
    float coordinateScaleError = abs(
            ATM_WORLD_UNIT_SCALE_KM
                    / (0.001
                            * ATM_WORLD_TO_ATMOSPHERE_SCALE)
                    - 1.0);
    float4 actualWorldOpticalDepth = actual.extinction
            * (worldDistance * ATM_WORLD_UNIT_SCALE_KM);
    float4 expectedWorldOpticalDepth = expected.extinction
            * (worldDistance * 0.001)
            * ATM_WORLD_TO_ATMOSPHERE_SCALE;

    atmosphereScaleOutput[index] = float4(
            modelError,
            geometryError,
            coordinateScaleError,
            relativeError(
                    actualWorldOpticalDepth,
                    expectedWorldOpticalDepth));
}
