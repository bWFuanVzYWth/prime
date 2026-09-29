#language slang 2026
module "atmosphere/shadow_resolve.slang";
import "atmosphere/view.slang";
import "atmosphere/shadow_math.slang";
import "ray_query.slang";
[[vk::binding(0, 1)]] StructuredBuffer<AtmFrame> frames;
[[vk::binding(4, 2)]] RWStructuredBuffer<uint4> cells;
[[vk::binding(0, 3)]] RaytracingAccelerationStructure acceleration;
[[vk::binding(1, 3)]] StructuredBuffer<uint4> textures;
[[vk::binding(2, 3)]] StructuredBuffer<uint> texels;
[[vk::binding(3, 3)]] StructuredBuffer<InstanceMaterial> materials;
[[vk::binding(4, 3)]] StructuredBuffer<TrianglePage> pages;
[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint index = id.z * 512u * 512u + id.y * 512u + id.x;
    AtmFrame f = frames[0];
    uint4 cell = cells[index];
    if (cell.x != asuint(f.epipole.z))
        return;
    float texelSize = sunShadowTexelSize(int(id.z));
    float2 camera = float2(dot(f.camera.xyz, f.shadowU.xyz), dot(f.camera.xyz, f.shadowV.xyz));
    int2 minimum = int2(floor(camera / texelSize)) - 256;
    int2 world = minimum + ((int2(id.xy) - minimum) & 511);
    if (cell.y == asuint(world.x) && cell.z == asuint(world.y))
        return;
    float plane = f.shadowU.w + 4096.0;
    float3 origin = normalize(f.sun.xyz) * plane +
                    f.shadowU.xyz * ((float(world.x) + 0.5) * texelSize) +
                    f.shadowV.xyz * ((float(world.y) + 0.5) * texelSize);
    TraceScene scene = {acceleration, textures, texels, materials, pages};
    float distance = traceBlockerDistance(scene, origin, -normalize(f.sun.xyz), 8192.0);
    float depth = distance < 8192.0 ? plane - distance : -1e20;
    cells[index] = uint4(cell.x, asuint(world.x), asuint(world.y), asuint(depth));
}
