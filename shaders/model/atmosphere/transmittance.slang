#language slang 2026
module "model/atmosphere/transmittance.slang";

import "model/atmosphere/sky.slang";
import "model/atmosphere/spectrum.slang";



static const int TRANSMITTANCE_STEPS = 128;

public void atmTransmittanceTexel(int2 pixel, int2 dimensions, out float4 low, out float4 high) {
    float2 uv = (float2(pixel) + float2(0.5)) / float2(dimensions);
    float h = sqrt(max(ATM_TOP_RADIUS_KM * ATM_TOP_RADIUS_KM
            - ATM_BOTTOM_RADIUS_KM * ATM_BOTTOM_RADIUS_KM, 0.0));
    float rho = h * uv.y;
    float radiusKm = sqrt(rho * rho + ATM_BOTTOM_RADIUS_KM * ATM_BOTTOM_RADIUS_KM);
    float minimumDistance = ATM_TOP_RADIUS_KM - radiusKm;
    float maximumDistance = rho + h;
    float distance = lerp(minimumDistance, maximumDistance, uv.x);
    float mu = distance == 0.0
            ? 1.0
            : (h * h - rho * rho - distance * distance) / (2.0 * radiusKm * distance);
    mu = clamp(mu, -1.0, 1.0);
    float3 origin = float3(0.0, radiusKm, 0.0);
    float3 direction = float3(sqrt(max(1.0 - mu * mu, 0.0)), mu, 0.0);
    float maximumT = atmRaySphere(origin, direction, ATM_TOP_RADIUS_KM);
    float stepLength = max(maximumT, 0.0) / float(TRANSMITTANCE_STEPS);
    float4 opticalDepthLow = float4(0.0);
    float4 opticalDepthHigh = float4(0.0);
    for (int index = 0; index < TRANSMITTANCE_STEPS; ++index) {
        float t = (float(index) + 0.5) * stepLength;
        float altitudeKm = length(origin + direction * t) - ATM_BOTTOM_RADIUS_KM;
        opticalDepthLow += atmCoefficients(0, altitudeKm).extinction * stepLength;
        opticalDepthHigh += atmCoefficients(1, altitudeKm).extinction * stepLength;
    }
    low = exp(-opticalDepthLow);
    high = exp(-opticalDepthHigh);
}
