#language slang 2026
module path_trace;
import "math/color.slang";
import "display/prime_drt.slang";
import "math/z_sobol.slang";
import "math/ray_offset.slang";
import "ray_query.slang";

struct Frame {
    float4 position; // w = tan(vertical FOV / 2)
    float4 forward;  // w = aspect ratio
    float4 right;
    float4 up;
    uint4 dimensions; // width, height, sample index, has geometry
    uint4 target; // x: host bottom-up; y: sample sequence; z: ceil(log2(max extent)); w: global seed
    float4 display;      // exposure multiplier, curve peak, encoded output peak, hue compensation
    float4 displayExtra; // x: saturation compensation
};
[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
[[vk::binding(2, 0)]] StructuredBuffer<uint4> textures; // offset, width, height, reserved
[[vk::binding(3, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;
[[vk::binding(5, 0)]] RWStructuredBuffer<float4> accumulation;
[[vk::binding(7, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(8, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
[[vk::push_constant]] ConstantBuffer<Frame> frame;

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
[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint width = frame.dimensions.x, height = frame.dimensions.y;
    if (id.x >= width || id.y >= height)
        return;
    uint pixel = id.y * width + id.x;
    // Fixed 2^8-sample sets; native 1080p fits the single-word index path.
    // Beyond a set, change the global scramble: no repeated indices and no claim
    // of an infinite single-net Sobol sequence.
    ZSobol sampler = makeZSobol(id.xy, frame.target.y & 0xffu, frame.target.z, 8,
                                frame.target.w ^ (frame.target.y >> 8));
    float2 screen = ((float2(id.xy) + sampler.sample2D(0)) / float2(width, height)) * 2.0 - 1.0;
    float3 origin = frame.position.xyz;
    float3 direction = normalize(frame.forward.xyz +
                                 frame.right.xyz * (screen.x * frame.forward.w * frame.position.w) -
                                 frame.up.xyz * (screen.y * frame.position.w));
    TraceScene resources = {scene, textures, texels, instanceMaterials, staticTrianglePages};
    float3 radiance = float3(0.0), throughput = float3(1.0);
    float3 sunDirection = normalize(float3(-0.45, 0.83, 0.33));
    for (uint bounce = 0; bounce < 4; ++bounce) {
        // Stable disjoint domains: primary jitter=0, then coverage/shadow/BSDF/roulette per vertex.
        uint domain = 1 + 4 * bounce;
        SurfaceHit hit = traceClosest(resources, origin, direction, sampler.bits1D(domain));
        if (!hit.found) {
            radiance += throughput * sky(direction);
            break;
        }
        SurfacePoint surface = hit.surface;
        if (dot(surface.normal, direction) > 0.0)
            surface.normal = -surface.normal;
        float3 albedo = sourceToWorking(saturate(hit.sourceColor.rgb));
        float cosine = saturate(dot(surface.normal, sunDirection));
        if (cosine > 0.0 && !traceOcclusion(resources, spawnRay(surface, sunDirection),
                                            sunDirection, sampler.bits1D(domain + 1)))
            radiance += throughput * albedo * linear709ToWorking(float3(3.8, 3.5, 3.0)) *
                        (cosine / 3.14159265359);
        throughput *= albedo;
        direction = cosineHemisphere(surface.normal, sampler.sample2D(domain + 2));
        origin = spawnRay(surface, direction);
        if (bounce >= 2) {
            float survival = clamp(workingLuminance(throughput), 0.05, 0.95);
            if (sampler.sample1D(domain + 3) >= survival)
                break;
            throughput /= survival;
        }
    }
    uint sample = frame.dimensions.z;
    float3 average =
            sample == 0 ? radiance
                        : (accumulation[pixel].rgb * float(sample) + radiance) / float(sample + 1);
    accumulation[pixel] = float4(average, 1.0);
    PrimeDRTParameters display = {frame.display.x, frame.display.y, frame.display.z,
                                  frame.display.w, frame.displayExtra.x};
    uint3 encoded = uint3(saturate(primeDRT(average, display)) * 255.0 + 0.5);
    uint2 targetPixel = uint2(id.x, frame.target.x != 0 ? height - 1 - id.y : id.y);
    outputImage[targetPixel] = float4(float3(encoded) / 255.0, 1.0);
}
