#language slang 2026
module "ray_query.slang";
import "math/ray_offset.slang";

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
    Triangle *triangles;
    uint texture;
    uint flags;
    uint tint;
    uint reserved0;
    uint reserved1;
    uint reserved2;
    float4 uv;
};
public struct TrianglePage {
    Triangle *triangles;
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
};
uint hash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352d;
    x ^= x >> 15;
    x *= 0x846ca68b;
    return x ^ (x >> 16);
}
float4 sourceColor(TraceScene resources, Triangle tri, float2 bary) {
    float3 b = float3(1.0 - bary.x - bary.y, bary.x, bary.y);
    float2 uv = tri.uv01.xy * b.x + tri.uv01.zw * b.y + tri.uv2 * b.z;
    uint4 texture = resources.textures[tri.texture];
    uint2 pixel = min(uint2(frac(uv) * float2(texture.yz)), texture.yz - 1);
    uint packed = resources.texels[texture.x + pixel.y * texture.y + pixel.x];
    float4 texel =
            float4(packed & 255, (packed >> 8) & 255, (packed >> 16) & 255, packed >> 24) / 255.0;
    return texel * (tri.c0 * b.x + tri.c1 * b.y + tri.c2 * b.z);
}
Triangle loadTriangle(TraceScene resources, uint instance, uint geometry, uint primitive) {
    if ((instance & 0x00800000) == 0)
        return resources.staticTrianglePages[instance + geometry].triangles[primitive];
    InstanceMaterial material = resources.instanceMaterials[instance & 0x007fffff];
    Triangle tri = material.triangles[primitive];
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
    return tri;
}
bool acceptsCoverage(TraceScene resources, Triangle tri, float2 bary, float3 point, uint raySeed) {
    if (tri.flags == 0)
        return true;
    float alpha = saturate(sourceColor(resources, tri, bary).a);
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
            Triangle candidate =
                    loadTriangle(resources, query.CandidateInstanceID(),
                                 query.CandidateGeometryIndex(), query.CandidatePrimitiveIndex());
            if (acceptsCoverage(resources, candidate, query.CandidateTriangleBarycentrics(),
                                origin + direction * query.CandidateTriangleRayT(), coverageSeed))
                query.CommitNonOpaqueTriangleHit();
        }
    }
    SurfaceHit hit;
    hit.found = query.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
    if (hit.found) {
        Triangle tri =
                loadTriangle(resources, query.CommittedInstanceID(), query.CommittedGeometryIndex(),
                             query.CommittedPrimitiveIndex());
        float2 bary = query.CommittedTriangleBarycentrics();
        hit.surface = reconstructSurface(tri.p0.xyz, tri.p1.xyz, tri.p2.xyz, bary,
                                         query.CommittedObjectToWorld3x4(),
                                         query.CommittedWorldToObject3x4());
        hit.sourceColor = sourceColor(resources, tri, bary);
    }
    return hit;
}
public bool traceOcclusion(TraceScene resources, float3 origin, float3 direction,
                           uint coverageSeed) {
    RayDesc ray;
    ray.Origin = origin;
    ray.Direction = direction;
    ray.TMin = 0.0;
    ray.TMax = 100000.0;
    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
    query.TraceRayInline(resources.acceleration, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, 0xff,
                         ray);
    while (query.Proceed()) {
        if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
            Triangle tri =
                    loadTriangle(resources, query.CandidateInstanceID(),
                                 query.CandidateGeometryIndex(), query.CandidatePrimitiveIndex());
            if (acceptsCoverage(resources, tri, query.CandidateTriangleBarycentrics(),
                                origin + direction * query.CandidateTriangleRayT(), coverageSeed))
                query.CommitNonOpaqueTriangleHit();
        }
    }
    return query.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
}
