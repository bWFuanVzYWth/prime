#language slang 2026
module "entry/primary/primary.raygeneration.slang";

import glsl;
import "prime_abi.slang";
import "contract/coordinate.slang";
import "contract/primary_color.slang";
import "math/color_space.slang";

[[vk::binding(0, 1)]]
[[vk::image_format("rgba8")]]
writeonly RWTexture2D<unorm float4> primePrimaryOutput;

layout(location = 0) rayPayloadEXT PrimePrimaryColorPayload primePrimaryPayload;

[shader("raygeneration")]
void main()
{
    uint2 pixel = DispatchRaysIndex().xy;
    float2 uv = (float2(pixel) + PRIME_PIXEL_CENTER_OFFSET)
            / float2(primePush.outputExtent);
    float2 ndc = primeCanonicalUvToClip(uv);
    float4 nearPoint = primePush.inverseViewProjection
            * float4(ndc, 1.0, 1.0);
    float4 farPoint = primePush.inverseViewProjection
            * float4(ndc, 0.0, 1.0);
    float3 direction = normalize(
            farPoint.xyz / farPoint.w - nearPoint.xyz / nearPoint.w);
    primePrimaryPayload.color = float3(0.0);
    primePrimaryPayload.hit = 0u;
    traceRayEXT(
            primeScene,
            gl_RayFlagsNoneEXT,
            0xff,
            0,
            1,
            0,
            primePush.cameraPosition,
            0.0,
            direction,
            1000000.0,
            0);
    float3 displayColor = primePrimaryPayload.hit == 0u
            ? float3(0.0)
            : primeEncodeSrgb(primeLinearRec2020ToLinearBt709(
                    primePrimaryPayload.color));
    primePrimaryOutput[pixel] = float4(clamp(displayColor, 0.0, 1.0), 1.0);
}
