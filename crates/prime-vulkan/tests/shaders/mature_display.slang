#language slang 2026
module mature_display;
import "display/exposure.slang";
import "display/hdr.slang";
import "display/image_space.slang";
import "atmosphere/starmap.slang";
[[vk::binding(0, 0)]] StructuredBuffer<float4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputs;
public float mdCpuAdapt(float previous, float target, float delta) {
    return primeAutoExposureAdapt(previous, target, delta);
}
public float mdCpuTarget(float measured, float minimum, float maximum, float strength) {
    return primeAutoExposureTargetEv(measured, minimum, maximum, strength);
}
public float mdCpuMeter(float3 radiance, float3 albedo, uint classification, float distance) {
    return primeAutoExposureMeteredBrightness(
            radiance, albedo, primeAutoExposureMaterialConfidence(classification, distance));
}
public float3 mdCpuHdr(float3 hdr, float3 baseline, float4 ui, float scale) {
    return primeHdrComposite(hdr, baseline, ui, scale);
}
public float3 mdCpuHdrLinear(float3 linearHdr, float3 baseline, float4 ui, float scale) {
    return primeHdrCompositeLinear(linearHdr, baseline, ui, scale);
}
public float3 mdCpuHdrDecode(float3 hdr) {
    return primeExtendedSrgbEotf(hdr);
}
public float mdCpuEotf(float encoded) {
    return primeExtendedSrgbEotf(encoded);
}
public uint mdCpuUiRow(uint y, uint height, bool bottomUp) {
    return primePresentUiPixel(uint2(0, y), uint2(1, height), bottomUp).y;
}
public uint mdCpuRrFootprint(float2 position) {
    return primeRrFootprintMask(position);
}
public float2 mdCpuStarmapUv(float3 sun, float latitude, float longitude, float3 ray) {
    return primeStarmapUv(primeCelestialFrame(sun, latitude, longitude), ray);
}
public float4 mdCpuFootprint(float2 dx, float2 dy, float2 extent) {
    PrimeStarmapFootprint result = primeStarmapFootprint(dx, dy, extent);
    return float4(result.stepUv, result.lod, float(result.taps));
}
public float3 mdCpuComposite(float3 scene, float3 stars, float coverage) {
    return primeCompositeStars(scene, stars, coverage);
}
[numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    float4 a = inputs[id.x * 2], b = inputs[id.x * 2 + 1];
    outputs[id.x * 5] = float4(mdCpuAdapt(a.x, a.y, a.z), mdCpuTarget(a.x, a.y, a.z, a.w),
                               mdCpuMeter(a.xyz, b.xyz, uint(b.w), a.w), mdCpuEotf(b.x));
    outputs[id.x * 5 + 1] = float4(mdCpuHdr(a.xyz, b.xyz, a, b.w), 1);
    outputs[id.x * 5 + 2] = mdCpuFootprint(a.xy, a.zw, b.xy);
    outputs[id.x * 5 + 3] =
            float4(mdCpuStarmapUv(a.xyz, b.x, b.y, b.xyz), mdCpuComposite(a.xyz, b.xyz, a.w).xy);
    outputs[id.x * 5 + 4] =
            float4(mdCpuHdrLinear(mdCpuHdrDecode(a.xyz), b.xyz, a, b.w),
                   float(mdCpuUiRow(uint(b.x), uint(b.y), b.z != 0.0) + mdCpuRrFootprint(a.xy)));
}
