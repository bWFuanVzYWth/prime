// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module starmap_poles;
import "atmosphere/starmap.slang";
[[vk::binding(0, 0)]] StructuredBuffer<float4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputs;
[[vk::binding(2, 0)]] Sampler2D<float4> source;
public float2 smCpuUv(PrimeCelestialFrame frame, float3 ray) {
    return primeStarmapUv(frame, ray);
}
public PrimeCelestialFrame smCpuFrame(float3 sun, float latitude, float longitude) {
    return primeCelestialFrame(sun, latitude, longitude);
}
public float2 smCpuOffset(uint tap) {
    return primeStarmapPolarOffset(tap);
}
public float smCpuLod(PrimeCelestialFrame frame, float3 ray, float3 dx, float3 dy,
                      float ordinaryLod) {
    return primeStarmapPolarLod(frame, ray, dx, dy, ordinaryLod, 8192);
}
public float2 smCpuTap(PrimeCelestialFrame frame, float3 ray, float3 dx, float3 dy, uint tap) {
    return primeStarmapPolarUv(frame, ray, dx, dy, tap);
}
public float3 smCpuSample(Sampler2D<float4> texture, PrimeCelestialFrame frame, float3 ray,
                          float3 dx, float3 dy, float lod) {
    return primeSampleStarmapPolar(texture, frame, ray, dx, dy, lod);
}
[numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    float4 a = inputs[id.x * 4], b = inputs[id.x * 4 + 1];
    float4 c = inputs[id.x * 4 + 2], d = inputs[id.x * 4 + 3];
    PrimeCelestialFrame frame = smCpuFrame(float3(0, 1, 0), a.w, b.w);
    float lod = smCpuLod(frame, a.xyz, b.xyz, c.xyz, d.w);
    outputs[id.x * 3] = float4(smCpuUv(frame, a.xyz), smCpuOffset(uint(d.x)));
    outputs[id.x * 3 + 1] = float4(smCpuTap(frame, a.xyz, b.xyz, c.xyz, uint(d.x)), lod, 0);
    outputs[id.x * 3 + 2] = float4(smCpuSample(source, frame, a.xyz, b.xyz, c.xyz, max(lod, 0)), 1);
}
