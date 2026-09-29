#language slang 2026
module "atmosphere/sky_update.slang";
import "atmosphere/spectrum.slang";
import "atmosphere/view.slang";
import "atmosphere/sky_rows.slang";
import "atmosphere/integrate.slang";
#include "atmosphere/model_bindings.slang"
[[vk::binding(0, 1)]] StructuredBuffer<AtmFrame> frames;
[[vk::binding(0, 2)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> skyOutput;
groupshared float rowCosine;
[numthreads(256, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    AtmFrame f = frames[0];
    if (id.x == 0) {
        float mu = atmSkyRowCosine(f.geometry.x, f.geometry.z, id.y);
        rowCosine = id.y >= 192u ? min(mu, f.geometry.y - 1e-7) : max(mu, f.geometry.y + 1e-7);
    }
    GroupMemoryBarrierWithGroupSync();
    float u = float(id.x) / 255.0;
    float a = u * u * u * u;
    float r = sqrt(max(1.0 - rowCosine * rowCosine, 0.0));
    float3 ray = float3(r * 2.0 * sqrt(max(a * (1.0 - a), 0.0)), rowCosine, r * (1.0 - 2.0 * a));
    float3 sun = float3(0, sin(f.geometry.z), cos(f.geometry.z));
    AtmTransport result = atmIntegrateAt(atmosphereModel(), ray, sun, f.geometry.x, 0, 96, true);
    skyOutput[id.xy] = float4(log(max(atmRadiance(result.light), float3(1e-30))), rowCosine);
}
