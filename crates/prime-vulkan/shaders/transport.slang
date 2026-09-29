#language slang 2026
module "transport.slang";
import "frame.slang";
import "math/color.slang";
import "math/z_sobol.slang";
import "math/ray_offset.slang";
import "ray_query.slang";

public struct TraceResult {
    public float3 radiance;
    public float depth;
    public float3 normal;
};
float powerHeuristic(float a, float b) {
    if (a >= b) {
        float ratio = b / a;
        return 1.0 / (1.0 + ratio * ratio);
    }
    float ratio = a / b;
    return ratio * ratio / (1.0 + ratio * ratio);
}
float3 sky(float3 direction) {
    return linear709ToWorking(lerp(float3(0.22, 0.27, 0.35), float3(0.36, 0.61, 1.0),
                                   saturate(direction.y * 0.5 + 0.5)));
}
float3 cosineHemisphere(float3 normal, float2 sample) {
    float radius = sqrt(sample.x);
    float angle = 6.28318530718 * sample.y;
    float3 tangent =
            normalize(cross(abs(normal.y) < 0.99 ? float3(0, 1, 0) : float3(1, 0, 0), normal));
    return normalize(tangent * (radius * cos(angle)) +
                     cross(normal, tangent) * (radius * sin(angle)) +
                     normal * sqrt(max(0.0, 1.0 - radius * radius)));
}

// Guides describe the very same jittered primary sample and alpha decision as noisy radiance.
public TraceResult traceSample(TraceScene resources, Frame frame, uint2 pixel, uint sequence) {
    TraceResult result = {float3(0), 3.402823466e38f, float3(0)};
    ZSobol sampler = makeZSobol(pixel, sequence & 0xffu, frame.target.z, 8,
                                frame.target.w ^ (sequence >> 8));
    float2 screen =
            ((float2(pixel) + sampler.sample2D(0)) / float2(frame.dimensions.xy)) * 2.0 - 1.0;
    float3 origin = frame.position.xyz;
    float3 direction = normalize(frame.forward.xyz +
                                 frame.right.xyz * (screen.x * frame.forward.w * frame.position.w) -
                                 frame.up.xyz * (screen.y * frame.position.w));
    float3 throughput = float3(1);
    float3 previous = origin;
    float previousPdf = 0;
    float3 sunDirection = normalize(float3(-0.45, 0.83, 0.33));
    for (uint bounce = 0; bounce < asuint(frame.displayExtra.z); ++bounce) {
        uint domain = 1 + 4 * bounce;
        SurfaceHit hit = traceClosest(resources, origin, direction, sampler.bits1D(domain));
        if (!hit.found) {
            result.radiance += throughput * sky(direction) * frame.up.w;
            break;
        }
        SurfacePoint surface = hit.surface;
        if (dot(surface.normal, direction) > 0)
            surface.normal = -surface.normal;
        if (bounce == 0) {
            result.depth = dot(surface.position - frame.position.xyz, frame.forward.xyz);
            result.normal = surface.normal;
        }
        float3 albedo = sourceToWorking(saturate(hit.sourceColor.rgb));
        if (lightFeatures != 0) {
            float lightPdf = bounce == 0 ? 0 : emissiveHitPdf(resources, hit, previous);
            float emissionWeight = lightPdf == 0 ? 1.0 : powerHeuristic(previousPdf, lightPdf);
            result.radiance += throughput * linear709ToWorking(hit.emission) * emissionWeight;
        }
        if (lightFeatures != 0 && hasLocalLights(resources)) {
            uint lightDomain = 512 + 4 * bounce;
            LightSample light = sampleLocalLight(resources, sampler.sample1D(lightDomain),
                                                 sampler.sample2D(lightDomain + 1),
                                                 sampler.bits1D(lightDomain + 2));
            float pdf;
            float lightCosine;
            float3 radiance = evaluateLocalLight(resources, surface, light,
                                                 sampler.bits1D(lightDomain + 3), pdf, lightCosine);
            if (pdf > 0) {
                float bsdfPdf = lightCosine / 3.14159265359;
                float weight = bounce + 1 == asuint(frame.displayExtra.z)
                                       ? 1.0
                                       : powerHeuristic(pdf, bsdfPdf);
                result.radiance += throughput * albedo * linear709ToWorking(radiance) *
                                   (weight * bsdfPdf / pdf);
            }
        }
        float cosine = saturate(dot(surface.normal, sunDirection));
        if (cosine > 0 && !traceOcclusion(resources, spawnRay(surface, sunDirection), sunDirection,
                                          sampler.bits1D(domain + 1)))
            result.radiance += throughput * albedo * linear709ToWorking(float3(3.8, 3.5, 3.0)) *
                               (frame.right.w * cosine / 3.14159265359);
        throughput *= albedo;
        direction = cosineHemisphere(surface.normal, sampler.sample2D(domain + 2));
        previous = surface.position;
        previousPdf = max(0.0, dot(surface.normal, direction)) / 3.14159265359;
        origin = spawnRay(surface, direction);
        if (bounce >= 2) {
            float survival = clamp(workingLuminance(throughput), 0.05, 0.95);
            if (sampler.sample1D(domain + 3) >= survival)
                break;
            throughput /= survival;
        }
    }
    return result;
}
