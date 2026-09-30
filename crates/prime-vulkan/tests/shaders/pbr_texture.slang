#language slang 2026
module pbr_texture_test;
import "ray_query.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputs;
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
    // Exercise the actual fixture AS through ray query. A bare acceleration descriptor would
    // otherwise imply RayTracingKHR instead of this device's enabled RayQueryKHR contract.
    RayDesc ray = {float3(-1.0, -1.0, 1.0), 0.0, float3(0.0, 0.0, -1.0), 2.0};
    RayQuery<RAY_FLAG_FORCE_OPAQUE> query;
    query.TraceRayInline(acceleration, RAY_FLAG_NONE, 0xff, ray);
    while (query.Proceed()) {}
    uint4 control = inputs[i * 4];
    float2 uv = asfloat(control.yz);
    float lod = asfloat(control.w);
    MaterialSample material = materialSource(resources, control.x, uv);
    float4 normal = (material.maps.z & 1u) != 0u
                            ? linearMaterial(resources, material.maps.x, material.uv, lod)
                            : float4(0.5, 0.5, 1.0, 0.0);
    float4 specular = (material.maps.z & 2u) != 0u
                              ? sampleTexture(resources.textures, resources.texels, material.maps.y,
                                              material.uv, 0.0)
                              : float4(0.0, 0.0, 0.0, 1.0);
    if ((material.maps.z & 2u) != 0u) {
        float4 continuous = linearMaterial(resources, material.maps.y, material.uv, lod);
        specular.r = continuous.r;
        specular.a = continuous.a;
    }
    SurfaceRecord record;
    record.triangle.p0 = float4(0.0, 0.0, 0.0, 1.0);
    record.triangle.p1 = float4(1.0, 0.0, 0.0, 1.0);
    record.triangle.p2 = float4(0.0, 1.0, 0.0, 1.0);
    record.triangle.c0 = record.triangle.c1 = record.triangle.c2 = float4(1.0);
    record.triangle.uv01 = float4(uv, uv + float2(1.0, 0.0));
    record.triangle.uv2 = uv + float2(0.0, 1.0);
    record.triangle.texture = control.x;
    record.identity = uint4(inputs[i * 4 + 2].w, 0u, 0u, 0u);
    record.positiveIorTexture = inputs[i * 4 + 3].x;
    record.emission = float4(asfloat(inputs[i * 4 + 2]).xyz, 0.0);
    record.negative = float4(1.5, 0.1, 0.2, 0.3);
    record.positive = float4(1.333, 0.4, 0.5, 0.6);
    SourceSample source;
    source.color = asfloat(inputs[i * 4 + 1]);
    source.uv = uv;
    source.lod = lod;
    uint4 base = uint4(round(sampleTexture(textures, texels, control.x, uv, 0.0) * 255.0));
    uint packedBase = base.x | (base.y << 8) | (base.z << 16) | (base.w << 24);
    outputs[i * 8] = material.maps;
    outputs[i * 8 + 1] = uint4(asuint(material.uv), query.CommittedStatus(), packedBase);
    outputs[i * 8 + 2] = asuint(normal);
    outputs[i * 8 + 3] = asuint(specular);
    outputs[i * 8 + 4] = asuint(float4(sourceEmission(resources, record, source, true), 0.0));
    outputs[i * 8 + 5] = asuint(float4(sourceEmission(resources, record, source, false), 0.0));
    SurfaceRecord boundary = boundaryIors(resources, record, float2(0.0), exp2(lod));
    outputs[i * 8 + 6] = asuint(boundary.negative);
    outputs[i * 8 + 7] = asuint(boundary.positive);
}
