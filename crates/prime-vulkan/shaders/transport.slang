#language slang 2026
module "transport.slang";
import "frame.slang";
import "math/color.slang";
import "math/z_sobol.slang";
import "math/ray_offset.slang";
import "ray_query.slang";
import "atmosphere/view.slang";

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
public TraceResult traceSample(TraceScene resources, AtmEnvironment environment, Frame frame,
                               uint2 pixel, uint sequence) {
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
    float primaryDistance = 0;
    float previousPdf = 0;
    float4 medium = float4(1, 0, 0, 0);
    float coneWidth = 0.0;
    float coneSpread =
            surfaceFeatures >= 2 ? 2.0 * frame.position.w / float(frame.dimensions.y) : 0.0;
    for (uint bounce = 0; bounce < asuint(frame.displayExtra.z); ++bounce) {
        uint domain = 1 + 4 * bounce;
        SurfaceHit hit = traceClosest(resources, origin, direction, sampler.bits1D(domain),
                                      coneWidth, coneSpread);
        if (!hit.found) {
            float3 escaped = atmSky(environment, direction) * frame.up.w;
            if (atmSunContains(environment, direction)) {
                float weight = previousPdf == 0 ? 1.0 : powerHeuristic(previousPdf, atmSunPdf());
                escaped += weight * atmSunRadiance(environment, direction) * frame.right.w;
            }
            result.radiance += throughput * escaped;
            break;
        }
        SurfacePoint surface = hit.surface;
        coneWidth += coneSpread * length(surface.position - origin);
        if (opticalFeatures != 0) {
            if (bounce == 0)
                medium = hit.thin ? float4(1, 0, 0, 0) : hit.incident;
            throughput *= exp(-medium.yzw * length(surface.position - origin));
        }
        if (dot(surface.normal, direction) > 0)
            surface.normal = -surface.normal;
        if (bounce == 0) {
            primaryDistance = medium.x == 1.0 ? length(surface.position - frame.position.xyz) : 0.0;
            result.depth = dot(surface.position - frame.position.xyz, frame.forward.xyz);
            result.normal = surface.normal;
        }
        float3 albedo = sourceToWorking(saturate(hit.sourceColor.rgb));
        if (lightFeatures != 0) {
            float lightPdf = previousPdf == 0 ? 0 : emissiveHitPdf(resources, hit, previous);
            float emissionWeight = lightPdf == 0 ? 1.0 : powerHeuristic(previousPdf, lightPdf);
            result.radiance += throughput * linear709ToWorking(hit.emission) * emissionWeight;
        }
        if (hit.optical) {
            float cosine = saturate(dot(surface.normal, -direction));
            float eta = hit.incident.x / hit.transmitted.x;
            float f;
            if (hit.thin) {
                float4 glass = hit.incident.x > hit.transmitted.x ? hit.incident : hit.transmitted;
                f = dielectricFresnel(cosine, medium.x, glass.x);
                f = 2.0 * f / (1.0 + f);
                if (sampler.sample1D(domain + 2) < f)
                    direction = reflect(direction, surface.normal);
                else
                    throughput *= exp(-glass.yzw * (0.0625 / max(cosine, 0.001)));
            } else {
                f = dielectricFresnel(cosine, hit.incident.x, hit.transmitted.x);
                if (sampler.sample1D(domain + 2) < f)
                    direction = reflect(direction, surface.normal);
                else {
                    direction = normalize(refract(direction, surface.normal, eta));
                    throughput *= eta * eta;
                    medium = hit.transmitted;
                }
            }
            previous = surface.position;
            previousPdf = 0;
            origin = spawnRay(surface, direction);
            continue;
        }
        if (lightFeatures != 0 && hasLocalLights(resources)) {
            uint lightDomain = 512 + 4 * bounce;
            LightSample light = sampleLocalLight(resources, sampler, bounce, surface.position);
            float pdf;
            float lightCosine;
            float3 radiance =
                    evaluateLocalLight(resources, surface, light, sampler.bits1D(lightDomain + 3),
                                       medium, pdf, lightCosine);
            if (pdf > 0) {
                float bsdfPdf = lightCosine / 3.14159265359;
                float weight = bounce + 1 == asuint(frame.displayExtra.z)
                                       ? 1.0
                                       : powerHeuristic(pdf, bsdfPdf);
                result.radiance += throughput * albedo * linear709ToWorking(radiance) *
                                   (weight * bsdfPdf / pdf);
            }
        }
        float3 sunDirection = atmSampleSun(environment, sampler.sample2D(1024 + bounce));
        float cosine = saturate(dot(surface.normal, sunDirection));
        if (cosine > 0) {
            float3 visibility =
                    traceTransmittance(resources, spawnRay(surface, sunDirection), sunDirection,
                                       sampler.bits1D(domain + 1), 100000.0, medium);
            if (any(visibility > 0.0)) {
                float bsdfPdf = cosine / 3.14159265359;
                float weight = bounce + 1 < asuint(frame.displayExtra.z)
                                       ? powerHeuristic(atmSunPdf(), bsdfPdf)
                                       : 1.0;
                result.radiance += throughput * albedo * atmSunRadiance(environment, sunDirection) *
                                   (weight * frame.right.w * bsdfPdf / atmSunPdf()) * visibility;
            }
        }
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
    if (primaryDistance > 0) {
        float3 s, t;
        atmAerial(environment, 0.5 * (screen + 1.0), primaryDistance, s, t);
        result.radiance = t * result.radiance + s * frame.right.w;
    }
    return result;
}
