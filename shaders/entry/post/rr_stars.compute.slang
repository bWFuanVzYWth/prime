// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "entry/post/rr_stars.compute.slang";

import "contract/coordinate.slang";
import "model/atmosphere/celestial.slang";
import "math/atmosphere_direction.slang";
import "math/starmap_filter.slang";
import "service/atmosphere/starmap_filter.slang";
import "model/atmosphere/sky.slang";

[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> starsOutput;
[[vk::binding(1, 0)]] Sampler2D starsTexture;
[[vk::binding(2, 0)]] [[vk::image_format("rgba16f")]] readonly RWTexture2D<float4> starsTransmittance;
[[vk::binding(3, 0)]] [[vk::image_format("r8ui")]] readonly RWTexture2D<uint> starsVisibility;

struct PrimeStarsPush {
    float4x4 clipToWorld;
    float4 sunEye;
    float4 settings;
    float2 jitter;
};
[[vk::push_constant]] ConstantBuffer<PrimeStarsPush> starsPush;

float3 starsDirection(float2 uv) {
    float2 clip = primeCanonicalUvToClip(uv);
    float4 nearPoint = starsPush.clipToWorld * float4(clip, 1.0, 1.0);
    float4 farPoint = starsPush.clipToWorld * float4(clip, 0.0, 1.0);
    return normalize(farPoint.xyz / farPoint.w - nearPoint.xyz / nearPoint.w);
}

float2 starsUv(PrimeCelestialFrame frame, float3 direction) {
    float2 equatorial = primeCelestialEquatorialCoordinates(frame, direction);
    return float2(frac(0.5 - equatorial.x / (2.0 * 3.141592653589793)),
            clamp(0.5 - equatorial.y / 3.141592653589793, 0.0, 1.0));
}

[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint width, height;
    starsOutput.GetDimensions(width, height);
    if (id.x >= width || id.y >= height) return;
    int2 pixel = int2(id.xy);
    float4 scene = starsOutput[pixel];
    if (!(scene.a < 1.0)) return;
    float2 screenUv = (float2(pixel) + 0.5) / float2(width, height);
    uint iw, ih;
    starsVisibility.GetDimensions(iw, ih);
    int2 inputPixel = int2(floor(screenUv * float2(iw, ih) - starsPush.jitter));
    // Preset F alpha can undershoot even inside opaque regions after a history reset. Veto
    // starlight only where the current 3x3 footprint is entirely foreground; mixed boundaries
    // and sky keep NGX's coverage, including temporally reconstructed thin foreground features.
    uint interior = 0x04u;
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++)
        interior &= starsVisibility[clamp(inputPixel + int2(x, y), int2(0), int2(iw, ih) - 1)];
    if (interior != 0u) return;
    float3 direction = starsDirection(screenUv);
    if (!atmDistantDirectionVisible(starsPush.sunEye.w, direction)) return;
    PrimeCelestialFrame frame = primeCelestialFrame(starsPush.sunEye.xyz,
            starsPush.settings.x, starsPush.settings.y);
    float2 uv = starsUv(frame, direction);
    float2 dx = primeStarmapWrappedDelta(starsUv(frame,
            starsDirection(screenUv + float2(0.5 / width, 0.0))), starsUv(frame,
            starsDirection(screenUv - float2(0.5 / width, 0.0))));
    float2 dy = primeStarmapWrappedDelta(starsUv(frame,
            starsDirection(screenUv + float2(0.0, 0.5 / height))), starsUv(frame,
            starsDirection(screenUv - float2(0.0, 0.5 / height))));
    uint tw, th;
    starsTexture.GetDimensions(tw, th);
    PrimeStarmapFootprint footprint = primeStarmapFootprint(dx, dy, float2(tw, th));
    float3 stars = primeSampleStarmap(starsTexture, uv, footprint);
    uint transWidth, transHeight;
    starsTransmittance.GetDimensions(transWidth, transHeight);
    float coordinate = primeAtmosphereDirectionCoordinate(direction.y) * float(transWidth - 1u);
    int first = int(coordinate);
    float3 transmittance = lerp(starsTransmittance[int2(first, 0)].rgb,
            starsTransmittance[int2(min(first + 1, int(transWidth) - 1), 0)].rgb, frac(coordinate));
    stars *= transmittance * starsPush.settings.z;
    starsOutput[pixel] = float4(primeCompositeStars(scene.rgb, stars, scene.a), scene.a);
}
