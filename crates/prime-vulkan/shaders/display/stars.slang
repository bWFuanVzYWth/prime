#language slang 2026
module "display/stars.slang";
import "atmosphere/starmap.slang";
import "math/color.slang";

[[vk::binding(0, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> linearOutput;
[[vk::binding(1, 0)]] Sampler2D<float4> starmap;
[[vk::binding(2, 0)]] Texture2D<float4> distantTransmittance;
[[vk::binding(3, 0)]] [[vk::image_format("r8")]] readonly RWTexture2D<float> primaryStatus;
struct StarsParameters {
    float4 forward; // xyz basis; w tan(vertical FOV/2)
    float4 right;   // xyz basis; w output aspect
    float4 up;
    float4 sun;       // xyz direction; w ground-horizon cosine
    float4 settings;  // latitude radians, solar longitude radians, radiance scale, linear709 output
    uint4 dimensions; // output.xy, input.zw
    float2 jitter;
    uint2 reserved;
};
[[vk::push_constant]] ConstantBuffer<StarsParameters> parameters;
float3 perspectiveRay(float2 uv) {
    float2 screen = uv * 2.0 - 1.0;
    return parameters.forward.xyz +
           parameters.right.xyz * (screen.x * parameters.forward.w * parameters.right.w) -
           parameters.up.xyz * (screen.y * parameters.forward.w);
}
float3 direction(float2 uv) {
    return normalize(perspectiveRay(uv));
}
[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= parameters.dimensions.xy))
        return;
    float4 scene = linearOutput[id.xy];
    if (!(scene.a < 1.0))
        return;
    float2 uv = (float2(id.xy) + 0.5) / float2(parameters.dimensions.xy);
    int2 inputPixel = int2(floor(uv * float2(parameters.dimensions.zw) - parameters.jitter));
    uint interior = 8u;
    for (int y = -1; y <= 1; ++y)
        for (int x = -1; x <= 1; ++x) {
            int2 pixel =
                    clamp(inputPixel + int2(x, y), int2(0), int2(parameters.dimensions.zw) - 1);
            interior &= uint(primaryStatus[pixel] * 255.0 + 0.5);
        }
    if (interior != 0)
        return;
    float3 ray = direction(uv);
    if (ray.y < 0 && ray.y <= parameters.sun.w)
        return;
    PrimeCelestialFrame frame =
            primeCelestialFrame(parameters.sun.xyz, parameters.settings.x, parameters.settings.y);
    float2 starUv = primeStarmapUv(frame, ray);
    float2 dx = primeStarmapWrappedDelta(
            primeStarmapUv(frame, direction(uv + float2(0.5 / parameters.dimensions.x, 0))),
            primeStarmapUv(frame, direction(uv - float2(0.5 / parameters.dimensions.x, 0))));
    float2 dy = primeStarmapWrappedDelta(
            primeStarmapUv(frame, direction(uv + float2(0, 0.5 / parameters.dimensions.y))),
            primeStarmapUv(frame, direction(uv - float2(0, 0.5 / parameters.dimensions.y))));
    PrimeStarmapFootprint footprint = primeStarmapFootprint(dx, dy, float2(16384, 8192));
    float3 rayStepX = parameters.right.xyz *
                      (2.0 * parameters.forward.w * parameters.right.w / parameters.dimensions.x);
    float3 rayStepY = parameters.up.xyz * (-2.0 * parameters.forward.w / parameters.dimensions.y);
    float3 pixelRay = perspectiveRay(uv);
    float polarLod = primeStarmapPolarLod(frame, pixelRay, rayStepX, rayStepY, footprint.lod, 8192);
    float3 stars;
    if (polarLod >= 0)
        stars = primeSampleStarmapPolar(starmap, frame, pixelRay, rayStepX, rayStepY, polarLod);
    else
        stars = primeSampleStarmap(starmap, starUv, footprint);
    float coordinate = (0.5 + 0.5 * sign(ray.y) * sqrt(abs(clamp(ray.y, -1.0, 1.0)))) * 8192.0;
    int first = int(coordinate);
    float3 transmittance =
            lerp(distantTransmittance.Load(int3(first, 0, 0)).rgb,
                 distantTransmittance.Load(int3(min(first + 1, 8192), 0, 0)).rgb, frac(coordinate));
    stars *= transmittance * parameters.settings.z;
    if (parameters.settings.w != 0)
        stars = workingToLinear709(stars);
    linearOutput[id.xy] = float4(primeCompositeStars(scene.rgb, stars, scene.a), scene.a);
}
