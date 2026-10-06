#language slang 2026
module optics_test;
import "ray_query.slang";
import "math/ray_offset.slang";
import "math/color.slang";
import "pbr/vertex.slang";
[[vk::binding(0, 0)]] StructuredBuffer<float4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputs;
[[vk::binding(2, 0)]] RaytracingAccelerationStructure acceleration;
[[vk::binding(3, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(4, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(5, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(6, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
[[vk::push_constant]] ConstantBuffer<uint4> config;
[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= config.y)
        return;
    TraceScene resources = {acceleration, textures, texels, instanceMaterials, staticTrianglePages};
    float4 origin = inputs[i * 3], direction = inputs[i * 3 + 1];
    float4 medium = float4(direction.w, inputs[i * 3 + 2].xyz);
    if (config.x == 4) {
        // Query the resident packed records, including word43 and the selected coating.
        // Only material adaptation is probed here; Full BSDF tests own the actual energy LUTs.
        SurfaceHit hit = traceClosest(resources, origin.xyz, direction.xyz, 19);
        if (!hit.found) {
            outputs[i * 3] = outputs[i * 3 + 1] = outputs[i * 3 + 2] = float4(0);
            return;
        }
        float3 normal = dot(hit.shadingNormal, direction.xyz) > 0.0 ? -hit.shadingNormal
                                                                    : hit.shadingNormal;
        PrimePbrVertex vertex = primePbrVertex(
                sourceToWorking(saturate(hit.sourceColor.rgb)), normal, hit.normalSample.a,
                hit.specularSample.rgb, (hit.materialFlags & 1) != 0, (hit.materialFlags & 2) != 0,
                hit.optical ? hit.thin : hit.materialThin, hit.optical, -direction.xyz);
        outputs[i * 3] =
                asfloat(uint4(1, hit.materialThin ? 1 : 0, hit.thin ? 1 : 0, hit.optical ? 1 : 0));
        outputs[i * 3 + 1] =
                asfloat(uint4(vertex.control, hit.materialFlags, hit.physicalFront ? 1 : 0,
                              uint(round(hit.specularSample.b * 255.0))));
        outputs[i * 3 + 2] = hit.sourceColor;
        return;
    }
    if (config.x == 2) {
        LightSample light = sampleLocalLight(resources, float4(.5), float2(.5), 19, origin.xyz);
        SurfacePoint receiver = {origin.xyz, direction.xyz, 0.0};
        float pdf;
        float3 radiance = evaluateLocalLight(resources, receiver, light, 19, medium, false, pdf);
        outputs[i * 3] = float4(radiance, pdf);
        outputs[i * 3 + 1] = float4(light.radiance, light.valid ? 1.0 : 0.0);
        outputs[i * 3 + 2] = float4(light.surface.position, light.areaPdf);
        return;
    }
    float4 terminalMedium;
    float3 transmission = traceTransmittance(resources, origin.xyz, direction.xyz, 19, origin.w,
                                             medium, terminalMedium);
    SurfaceHit hit = traceClosest(resources, origin.xyz, direction.xyz, 19);
    outputs[i * 3] = float4(transmission, hit.found && hit.optical ? 1.0 : 0.0);
    outputs[i * 3 + 1] = hit.found
                                 ? float4(hit.incident.x, hit.transmitted.x,
                                          dielectricFresnel(1.0, hit.incident.x, hit.transmitted.x),
                                          hit.thin ? 1.0 : 0.0)
                                 : float4(0);
    if (config.x == 3) {
        outputs[i * 3 + 1] =
                hit.found ? float4(primeThinInsideMedium(hit).x, primeThinOutsideMedium(hit).x,
                                   hit.thin ? 1.0 : 0.0, hit.physicalFront ? 1.0 : 0.0)
                          : float4(0);
    }
    outputs[i * 3 + 2] = config.x != 0 ? terminalMedium : (hit.found ? hit.sourceColor : float4(0));
}
