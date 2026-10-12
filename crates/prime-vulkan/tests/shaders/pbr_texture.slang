#language slang 2026
module pbr_texture_test;
import "ray_query.slang";
import "math/ray_offset.slang";

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
    TraceScene resources = {acceleration,        textures,        texels, instanceMaterials,
                            staticTrianglePages, float2(0.0, 1.0)};
    uint testFlags = inputs[i * 4 + 3].y;
    uint encodedEV = testFlags >> 16;
    float ev = encodedEV == 0u ? 0.0 : float(int(encodedEV) - 128);
    resources.emissionSettings = float2(asfloat(inputs[i * 4 + 3].w), exp2(ev));
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
    float4 normal = (material.maps.z & PRIME_MATERIAL_NORMAL) != 0u
                            ? sampleMaterial(resources, material.maps.x, material.uv, lod)
                            : float4(128.0 / 255.0, 128.0 / 255.0, 0.0, 85.0 / 255.0);
    if ((material.maps.z & PRIME_MATERIAL_AUX) != 0u)
        normal.a = sampleTexture(textures, texels, material.maps.x, material.uv, 0.0).a;
    float4 specular = (material.maps.z & PRIME_MATERIAL_MERS) != 0u
                              ? sampleMaterial(resources, material.maps.y, material.uv, lod)
                              : float4(0.0, 0.0, 0.9, 0.0);
    SurfaceRecord record = {};
    record.triangle.p0 = float4(0.0, 0.0, 0.0, 1.0);
    record.triangle.p1 = float4(1.0, 0.0, 0.0, 1.0);
    record.triangle.p2 = float4(0.0, 1.0, 0.0, 1.0);
    record.triangle.c0 = record.triangle.c1 = record.triangle.c2 = asfloat(inputs[i * 4 + 1]);
    record.triangle.uv01 = float4(uv, uv + float2(1.0, 0.0));
    record.triangle.uv2 = uv + float2(0.0, 1.0);
    record.triangle.texture = control.x;
    record.triangle.flags = (testFlags & 256u) != 0u ? 4u : 0u;
    record.identity = uint4(inputs[i * 4 + 2].w, 0u, 0xffffffffu, testFlags & 255u);
    record.positiveIorTexture = inputs[i * 4 + 3].x;
    record.materialControl = 0u; // Synthetic material query has no CPU source topology proof.
    record.emission = float4(asfloat(inputs[i * 4 + 2]).xyz, asfloat(inputs[i * 4 + 3].z));
    record.negative = float4(1.5, 0.1, 0.2, 0.3);
    record.positive = float4(1.333, 0.4, 0.5, 0.6);
    SourceSample source = sourceSample(resources, record, float2(0.0), 0.0);
    source.lod = lod;
    float4 baseColor = sampleTexture(textures, texels, control.x, uv, 0.0);
    if (material.colorTexture != control.x)
        baseColor.rgb =
                sampleTexture(textures, texels, material.colorTexture, material.uv, 0.0).rgb;
    uint4 base = uint4(round(baseColor * 255.0));
    uint packedBase = base.x | (base.y << 8) | (base.z << 16) | (base.w << 24);
    outputs[i * 12] = material.maps;
    outputs[i * 12 + 1] = uint4(asuint(material.uv), query.CommittedStatus(), packedBase);
    outputs[i * 12 + 2] = asuint(normal);
    outputs[i * 12 + 3] = asuint(specular);
    outputs[i * 12 + 4] = asuint(float4(sourceEmission(resources, record, source, true), 0.0));
    outputs[i * 12 + 5] = asuint(float4(sourceEmission(resources, record, source, false), 0.0));
    SurfaceRecord boundary = boundaryIors(resources, record, float2(0.0), exp2(lod));
    outputs[i * 12 + 6] = asuint(boundary.negative);
    outputs[i * 12 + 7] = asuint(boundary.positive);
    // The same physical triangle producer as actual hits, with the footprint chosen to
    // exercise the requested LOD even when the atlas and sprite dimensions differ.
    uint4 image = textures[control.x * 4];
    float footprint = exp2(lod) / float(max(image.y, image.z));
    SurfacePoint surface = {float3(0.0), float3(0.0, 0.0, 1.0), 0.0};
    uint selected;
    SurfaceHit hit = resolveSurfaceHit(resources, record, 0u, 0u, 0u, float2(0.0), true, surface,
                                       float3x4(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0), 0u,
                                       float3(0.0, 0.0, -1.0), footprint, float3(0.0), 0xffffffffu,
                                       selected);
    outputs[i * 12 + 8] = asuint(hit.normalSample);
    outputs[i * 12 + 9] = asuint(hit.specularSample);
    outputs[i * 12 + 10] = asuint(float4(hit.emission, 0.0));
    outputs[i * 12 + 11] = asuint(hit.sourceColor);
}
