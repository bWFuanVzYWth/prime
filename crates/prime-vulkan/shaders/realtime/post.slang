#language slang 2026
module "realtime/post.slang";
import "realtime/post_parameters.slang";
import "math/z_sobol.slang";
import "atmosphere/aerial.slang";

// Linear FP32 until the explicitly selected output boundary. No tracing/BSDF dependency.
public float3 primeComposeRealtime(AtmAerial environment, PostParameters parameters, uint2 pixel) {
    uint index = pixel.y * parameters.sampling.x + pixel.x;
    float4 prefix = parameters.prefixAddress[index];
    float3 radiance = prefix.xyz + parameters.tailAddress[index].xyz;
    if (prefix.w > 0.0) {
        float2 offset;
        if (asuint(parameters.sample.w) != 0) {
            offset = 0.5 + parameters.sample.xy;
        } else {
            uint sequence = parameters.sampling.z;
            ZSobol sampler = makeZSobolS8(pixel, sequence & 0xffu, asuint(parameters.sample.z),
                                          parameters.sampling.w ^ (sequence >> 8));
            offset = sampler.sample2D(0);
        }
        float2 sampleUv = (float2(pixel) + offset) / float2(parameters.sampling.xy);
        float3 s, t;
        atmAerial(environment, sampleUv, prefix.w, s, t);
        radiance = t * radiance + s * parameters.extra.z;
    }
    return float3(isnan(radiance.x) || isinf(radiance.x) ? 0.0 : max(radiance.x, 0.0),
                  isnan(radiance.y) || isinf(radiance.y) ? 0.0 : max(radiance.y, 0.0),
                  isnan(radiance.z) || isinf(radiance.z) ? 0.0 : max(radiance.z, 0.0));
}
