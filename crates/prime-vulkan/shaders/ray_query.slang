#language slang 2026
module "ray_query.slang";
import "math/ray_offset.slang";

// Vulkan pipeline specialization removes unused record/light paths before GPU execution.
// Test entry points use the complete semantics by default.
[[vk::constant_id(0)]] public const uint surfaceFeatures = 1;
[[vk::constant_id(1)]] public const uint lightFeatures = 1;

// GPU records are shared with Rust. Only entry points assign descriptor bindings.
public struct Triangle {
    float4 p0;
    float4 p1;
    float4 p2;
    float4 c0;
    float4 c1;
    float4 c2;
    float4 uv01;
    float2 uv2;
    uint texture;
    uint flags;
};
public struct InstanceMaterial {
    uint *quads;
    uint texture;
    uint flags;
    uint tint;
    uint reserved0;
    uint reserved1;
    uint reserved2;
    float4 uv;
};
public struct SurfaceRecord {
    Triangle triangle;
    float4 uvU;
    float4 uvV;
    float4 emission;
    uint4 identity; // negative medium, positive medium, emitter, repeat/sided flags
};
public struct LightNode {
    float3 lower;
    float power;
    float3 upper;
    uint child;
};
public struct TrianglePage {
    uint *quads;
    uint format; // 0: ordinary 176B quad; 1: extended 240B surface
    uint lightCount;
    LightNode *nodes;
    uint *emitters;
    LightNode *worldNodes;
    float worldPower;
    uint reserved;
    float4 origin;
};

public struct TraceScene {
    public RaytracingAccelerationStructure acceleration;
    public StructuredBuffer<uint4> textures;
    public StructuredBuffer<uint> texels;
    public StructuredBuffer<InstanceMaterial> instanceMaterials;
    public StructuredBuffer<TrianglePage> staticTrianglePages;
};
public struct SurfaceHit {
    public bool found;
    public SurfacePoint surface;
    public float4 sourceColor;
    public float3 emission;
    public float emitterAreaWeight;
};
uint hash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352d;
    x ^= x >> 15;
    x *= 0x846ca68b;
    return x ^ (x >> 16);
}
float4 sourceColor(TraceScene resources, SurfaceRecord record, float2 bary) {
    Triangle tri = record.triangle;
    float3 b = float3(1.0 - bary.x - bary.y, bary.x, bary.y);
    float2 uv = tri.uv01.xy * b.x + tri.uv01.zw * b.y + tri.uv2 * b.z;
    if ((record.identity.w & 1) != 0) {
        float2 tile = frac(uv);
        uv = float2(dot(record.uvU.xy, tile) + record.uvU.z,
                    dot(record.uvV.xy, tile) + record.uvV.z);
    }
    uint4 texture = resources.textures[tri.texture];
    uint2 pixel = min(uint2(frac(uv) * float2(texture.yz)), texture.yz - 1);
    uint packed = resources.texels[texture.x + pixel.y * texture.y + pixel.x];
    float4 texel =
            float4(packed & 255, (packed >> 8) & 255, (packed >> 16) & 255, packed >> 24) / 255.0;
    return texel * (tri.c0 * b.x + tri.c1 * b.y + tri.c2 * b.z);
}
SurfaceRecord loadQuad(uint *base, uint format, uint primitive) {
    uint words = 44 + format * 16;
    uint *record = base + (primitive >> 1) * words;
    bool second = (primitive & 1) != 0;
    uint a = second ? 2 : 0;
    uint b = second ? 3 : 1;
    uint c = second ? 0 : 2;
    float4 *positions = (float4 *)record;
    float4 *colors = (float4 *)(record + 16);
    float2 *uvs = (float2 *)(record + 32);
    Triangle t;
    t.p0 = positions[a];
    t.p1 = positions[b];
    t.p2 = positions[c];
    t.uv01 = float4(uvs[a], uvs[b]);
    t.uv2 = uvs[c];
    t.texture = record[40];
    t.flags = record[41];
    t.c0 = colors[a];
    t.c1 = colors[b];
    t.c2 = colors[c];
    SurfaceRecord result;
    result.triangle = t;
    result.uvU = float4(0);
    result.uvV = float4(0);
    result.emission = float4(0);
    result.identity = uint4(0);
    if (surfaceFeatures != 0 && format != 0) {
        float4 *extra = (float4 *)(record + 44);
        result.uvU = extra[0];
        result.uvV = extra[1];
        result.emission = extra[2];
        result.identity = asuint(extra[3]);
    }
    return result;
}
SurfaceRecord loadSurface(TraceScene resources, uint instance, uint geometry, uint primitive) {
    if ((instance & 0x00800000) == 0) {
        TrianglePage page = resources.staticTrianglePages[instance + geometry];
        return loadQuad(page.quads, page.format, primitive);
    }
    InstanceMaterial material = resources.instanceMaterials[instance & 0x007fffff];
    SurfaceRecord result = loadQuad(material.quads, 0, primitive);
    Triangle tri = result.triangle;
    if (material.texture != 0xffffffff)
        tri.texture = material.texture;
    if (material.flags != 0xffffffff)
        tri.flags = material.flags;
    // Source vertices and instance tint are encoded bytes. Match vanilla's
    // integer channel product BEFORE interpolation, then texture and EOTF.
    float4 tint = float4(material.tint & 255, (material.tint >> 8) & 255,
                         (material.tint >> 16) & 255, material.tint >> 24);
    tri.c0 = floor(round(tri.c0 * 255.0) * tint / 255.0) / 255.0;
    tri.c1 = floor(round(tri.c1 * 255.0) * tint / 255.0) / 255.0;
    tri.c2 = floor(round(tri.c2 * 255.0) * tint / 255.0) / 255.0;
    tri.uv01.xy = tri.uv01.xy * material.uv.xy + material.uv.zw;
    tri.uv01.zw = tri.uv01.zw * material.uv.xy + material.uv.zw;
    tri.uv2 = tri.uv2 * material.uv.xy + material.uv.zw;
    result.triangle = tri;
    return result;
}
bool acceptsCoverage(TraceScene resources, SurfaceRecord record, float2 bary, float3 point,
                     uint raySeed) {
    Triangle tri = record.triangle;
    if (tri.flags == 0)
        return true;
    float alpha = saturate(sourceColor(resources, record, bary).a);
    if (tri.flags == 1)
        return alpha >= 0.1;
    // Alpha blend is stochastic coverage, not refraction/absorption. Quantized
    // coincident hits share one event, including emitted front/back fluid faces.
    // Intentionally correlates distinct surfaces at the same quantized position.
    uint3 cell = asuint(int3(floor(point * 256.0 + 0.5)));
    uint event =
            hash(raySeed ^ hash(cell.x) ^ hash(cell.y + 0x9e3779b9) ^ hash(cell.z + 0x85ebca6b));
    return float(event >> 8) * (1.0 / 16777216.0) < alpha;
}

public SurfaceHit traceClosest(TraceScene resources, float3 origin, float3 direction,
                               uint coverageSeed) {
    RayDesc ray;
    ray.Origin = origin;
    ray.Direction = direction;
    ray.TMin = 0.0;
    ray.TMax = 100000.0;
    RayQuery<RAY_FLAG_NONE> query;
    query.TraceRayInline(resources.acceleration, RAY_FLAG_NONE, 0xff, ray);
    while (query.Proceed()) {
        if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
            SurfaceRecord candidate =
                    loadSurface(resources, query.CandidateInstanceID(),
                                query.CandidateGeometryIndex(), query.CandidatePrimitiveIndex());
            if (acceptsCoverage(resources, candidate, query.CandidateTriangleBarycentrics(),
                                origin + direction * query.CandidateTriangleRayT(), coverageSeed))
                query.CommitNonOpaqueTriangleHit();
        }
    }
    SurfaceHit hit;
    hit.found = query.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
    if (hit.found) {
        SurfaceRecord record =
                loadSurface(resources, query.CommittedInstanceID(), query.CommittedGeometryIndex(),
                            query.CommittedPrimitiveIndex());
        Triangle tri = record.triangle;
        float2 bary = query.CommittedTriangleBarycentrics();
        uint instance = query.CommittedInstanceID();
        // Every static page has a producer-proven translation, for both record strides.
        if ((instance & 0x00800000) == 0)
            hit.surface = reconstructStaticSurface(
                    tri.p0.xyz, tri.p1.xyz, tri.p2.xyz, bary,
                    resources.staticTrianglePages[instance + query.CommittedGeometryIndex()]
                            .origin.xyz);
        else
            hit.surface = reconstructSurface(tri.p0.xyz, tri.p1.xyz, tri.p2.xyz, bary,
                                             query.CommittedObjectToWorld3x4(),
                                             query.CommittedWorldToObject3x4());
        hit.sourceColor = sourceColor(resources, record, bary);
        hit.emission = ((record.identity.w & 2) != 0 || dot(hit.surface.normal, direction) < 0)
                               ? record.emission.rgb
                               : float3(0);
        hit.emitterAreaWeight = record.emission.w;
    }
    return hit;
}
bool traceOcclusionRange(TraceScene resources, float3 origin, float3 direction, uint coverageSeed,
                         float limit) {
    RayDesc ray;
    ray.Origin = origin;
    ray.Direction = direction;
    ray.TMin = 0.0;
    ray.TMax = limit;
    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
    query.TraceRayInline(resources.acceleration, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, 0xff,
                         ray);
    while (query.Proceed()) {
        if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
            SurfaceRecord tri =
                    loadSurface(resources, query.CandidateInstanceID(),
                                query.CandidateGeometryIndex(), query.CandidatePrimitiveIndex());
            if (acceptsCoverage(resources, tri, query.CandidateTriangleBarycentrics(),
                                origin + direction * query.CandidateTriangleRayT(), coverageSeed))
                query.CommitNonOpaqueTriangleHit();
        }
    }
    return query.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
}

public bool traceOcclusion(TraceScene resources, float3 origin, float3 direction,
                           uint coverageSeed) {
    return traceOcclusionRange(resources, origin, direction, coverageSeed, 100000.0);
}

// Atmosphere clipmap: closest blocker without surface reconstruction. Alpha uses the
// current scene's coverage contract, with a fixed seed for a stable cached column.
public float traceBlockerDistance(TraceScene resources, float3 origin, float3 direction,
                                  float limit) {
    RayDesc ray = {origin, 0.0, direction, limit};
    RayQuery<RAY_FLAG_NONE> query;
    query.TraceRayInline(resources.acceleration, RAY_FLAG_NONE, 0xff, ray);
    while (query.Proceed()) {
        if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
            SurfaceRecord tri =
                    loadSurface(resources, query.CandidateInstanceID(),
                                query.CandidateGeometryIndex(), query.CandidatePrimitiveIndex());
            if (acceptsCoverage(resources, tri, query.CandidateTriangleBarycentrics(),
                                origin + direction * query.CandidateTriangleRayT(), 0))
                query.CommitNonOpaqueTriangleHit();
        }
    }
    return query.CommittedStatus() == COMMITTED_TRIANGLE_HIT ? query.CommittedRayT() : limit;
}

public bool hasLocalLights(TraceScene resources) {
    return resources.staticTrianglePages[0].lightCount != 0;
}

// Power-weighted baseline. Residual uniform mass is passed from the world leaf to its local
// tree. Bounds are resident, but a position-dependent proposal is not implied by this version.
uint selectLight(LightNode *nodes, inout float sample) {
    LightNode node = nodes[0];
    float weight = sample * node.power;
    while ((node.child & 0x80000000u) == 0) {
        LightNode left = nodes[node.child];
        if (weight < left.power)
            node = left;
        else {
            weight -= left.power;
            node = nodes[node.child + 1];
        }
    }
    sample = min(weight / node.power, asfloat(0x3f7fffffu));
    return node.child & 0x7fffffffu;
}

public struct LightSample {
    public bool valid;
    public SurfacePoint surface;
    public float3 radiance;
    public float areaPdf;
    public uint page;
    public uint emitter;
    public bool twoSided;
};

public LightSample sampleLocalLight(TraceScene resources, float selector, float2 sample,
                                    uint coverageSeed) {
    LightSample result;
    result.valid = false;
    TrianglePage world = resources.staticTrianglePages[0];
    if (world.lightCount == 0)
        return result;
    result.page = selectLight(world.worldNodes, selector);
    TrianglePage page = resources.staticTrianglePages[result.page];
    result.emitter = selectLight(page.nodes, selector);
    uint *light = page.emitters + result.emitter * 60;
    float firstFraction = asfloat(light[3]);
    bool first = firstFraction == 1.0 || sample.x < firstFraction;
    float u = first ? sample.x / firstFraction : (sample.x - firstFraction) / (1.0 - firstFraction);
    SurfaceRecord emitter = loadQuad(light, 1, first ? 0 : 1);
    float root = sqrt(u);
    float2 bary = float2(root * (1.0 - sample.y), root * sample.y);
    float3 t = page.origin.xyz;
    float3x4 toWorld = float3x4(1, 0, 0, t.x, 0, 1, 0, t.y, 0, 0, 1, t.z);
    float3x4 toLocal = float3x4(1, 0, 0, -t.x, 0, 1, 0, -t.y, 0, 0, 1, -t.z);
    Triangle tri = emitter.triangle;
    result.surface = reconstructSurface(tri.p0.xyz, tri.p1.xyz, tri.p2.xyz, bary, toWorld, toLocal);
    result.valid = acceptsCoverage(resources, emitter, bary, result.surface.position, coverageSeed);
    result.radiance = emitter.emission.rgb;
    result.areaPdf = emitter.emission.w / world.worldPower;
    result.twoSided = (emitter.identity.w & 2) != 0;
    return result;
}

public float emissiveHitPdf(TraceScene resources, SurfaceHit hit, float3 previous) {
    if (hit.emitterAreaWeight == 0)
        return 0;
    float3 delta = hit.surface.position - previous;
    float distanceSquared = dot(delta, delta);
    float cosine = abs(dot(hit.surface.normal, normalize(delta)));
    return cosine > 0 ? (hit.emitterAreaWeight / resources.staticTrianglePages[0].worldPower) *
                                distanceSquared / cosine
                      : 0;
}

public float3 evaluateLocalLight(TraceScene resources, SurfacePoint receiver, LightSample light,
                                 uint coverageSeed, out float pdf, out float cosine) {
    pdf = 0;
    cosine = 0;
    if (!light.valid)
        return float3(0);
    float3 delta = light.surface.position - receiver.position;
    float distanceSquared = dot(delta, delta);
    if (distanceSquared == 0)
        return float3(0);
    float3 direction = delta * rsqrt(distanceSquared);
    cosine = max(0.0, dot(receiver.normal, direction));
    float lightCosine = dot(light.surface.normal, -direction);
    if (light.twoSided)
        lightCosine = abs(lightCosine);
    if (cosine == 0 || lightCosine <= 0)
        return float3(0);
    pdf = light.areaPdf * distanceSquared / lightCosine;
    float3 begin = spawnRay(receiver, direction);
    float3 end = spawnRay(light.surface, -direction);
    float3 segment = end - begin;
    float limit = length(segment);
    if (limit == 0)
        return float3(0);
    return !traceOcclusionRange(resources, begin, segment / limit, coverageSeed, limit)
                   ? light.radiance
                   : float3(0);
}
