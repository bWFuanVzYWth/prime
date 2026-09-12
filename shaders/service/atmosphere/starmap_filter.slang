// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "service/atmosphere/starmap_filter.slang";
import "math/starmap_filter.slang";

public float3 primeSampleStarmap(Sampler2D texture, float2 uv, PrimeStarmapFootprint footprint) {
    float3 value = float3(0.0);
    for (uint tap = 0u; tap < footprint.taps; tap++) {
        float offset = float(tap) + 0.5 - 0.5 * float(footprint.taps);
        value += texture.SampleLevel(uv + footprint.stepUv * offset, footprint.lod).rgb;
    }
    return value / float(footprint.taps);
}
