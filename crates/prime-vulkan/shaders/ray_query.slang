#language slang 2026
module "ray_query.slang";
__exported import "trace/closest.slang";
import "math/ray_offset.slang";
import "math/transport.slang";
import "math/z_sobol.slang";
import "grid_sampling.slang";

[[vk::constant_id(1)]] public const uint lightFeatures = 1;

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
            tri = selectMaterial(resources, tri, query.CandidateTriangleFrontFace(),
                                 query.CandidateTriangleBarycentrics(),
                                 origin + direction * query.CandidateTriangleRayT(), coverageSeed);
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

public float dielectricFresnel(float cosine, float etaI, float etaT) {
    if (etaI == etaT)
        return 0.0;
    float sinT2 = (etaI * etaI) / (etaT * etaT) * max(0.0, 1.0 - cosine * cosine);
    if (sinT2 >= 1.0)
        return 1.0;
    float cosT = sqrt(1.0 - sinT2);
    float a = (etaI * cosine - etaT * cosT) / (etaI * cosine + etaT * cosT);
    float b = (etaT * cosine - etaI * cosT) / (etaT * cosine + etaI * cosT);
    return 0.5 * (a * a + b * b);
}
// Boundary moments integrate absorption independent of BVH candidate traversal order.
// This is the straight visibility segment for NEE; refracted paths use the BSDF below.
public float3 traceTransmittance(TraceScene resources, float3 origin, float3 direction, uint seed,
                                 float limit, float4 startingMedium) {
    if (opticalFeatures == 0)
        return traceOcclusionRange(resources, origin, direction, seed, limit) ? float3(0)
                                                                              : float3(1);
    RayDesc ray = {origin, 0.0, direction, limit};
    // Optical boundaries are not committed: an unobstructed segment still visits every
    // boundary. Any accepted ordinary blocker makes all remaining moments irrelevant.
    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
    query.TraceRayInline(resources.acceleration, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, 0xff,
                         ray);
    float3 moment = float3(0), terminal = startingMedium.yzw;
    float last = -1, fresnel = 1;
    while (query.Proceed()) {
        if (query.CandidateType() != CANDIDATE_NON_OPAQUE_TRIANGLE)
            continue;
        SurfaceRecord record =
                loadSurface(resources, query.CandidateInstanceID(), query.CandidateGeometryIndex(),
                            query.CandidatePrimitiveIndex());
        float distance = query.CandidateTriangleRayT();
        bool front = query.CandidateTriangleFrontFace();
        float2 bary = query.CandidateTriangleBarycentrics();
        record = boundaryIors(resources, record, bary, 0.0);
        record =
                selectMaterial(resources, record, front, bary, origin + direction * distance, seed);
        if ((record.identity.w & 128) == 0) {
            if (acceptsCoverage(resources, record, bary, origin + direction * distance, seed))
                query.CommitNonOpaqueTriangleHit();
            continue;
        }
        float4 from = front ? record.positive : record.negative,
               to = front ? record.negative : record.positive;
        Triangle t = record.triangle;
        float3 n = normalize(cross(t.p1.xyz - t.p0.xyz, t.p2.xyz - t.p0.xyz));
        float cosine = abs(dot(n, direction));
        if ((record.identity.w & 64) != 0) {
            float4 glass =
                    record.negative.x > record.positive.x ? record.negative : record.positive;
            float f = dielectricFresnel(cosine, startingMedium.x, glass.x);
            fresnel *= (1.0 - f) / (1.0 + f);
            moment += glass.yzw * (0.0625 / max(cosine, 0.001));
        } else {
            fresnel *= 1.0 - dielectricFresnel(cosine, from.x, to.x);
            moment += distance * (from.yzw - to.yzw);
            if (distance > last) {
                last = distance;
                terminal = to.yzw;
            }
        }
    }
    return query.CommittedStatus() == COMMITTED_TRIANGLE_HIT
                   ? float3(0)
                   : fresnel * exp(-max(float3(0), moment + terminal * limit));
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
            tri = selectMaterial(resources, tri, query.CandidateTriangleFrontFace(),
                                 query.CandidateTriangleBarycentrics(),
                                 origin + direction * query.CandidateTriangleRayT(), 0);
            if ((tri.identity.w & 128) == 0 &&
                acceptsCoverage(resources, tri, query.CandidateTriangleBarycentrics(),
                                origin + direction * query.CandidateTriangleRayT(), 0))
                query.CommitNonOpaqueTriangleHit();
        }
    }
    return query.CommittedStatus() == COMMITTED_TRIANGLE_HIT ? query.CommittedRayT() : limit;
}

public bool hasLocalLights(TraceScene resources) {
    return resources.staticTrianglePages[0].lightCount != 0;
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

public LightSample sampleLocalLight(TraceScene resources, float4 selectors, float2 sample,
                                    uint coverageSeed, float3 receiver) {
    LightSample result = {};
    TrianglePage world = resources.staticTrianglePages[0];
    if (world.lightCount == 0)
        return result;
    LightGrid grid = *((LightGrid *)world.lightGrid);
    float selectionPdf;
    uint selectedLight = selectGridLight(grid, receiver, selectors, selectionPdf);
    GridEmitter reference = grid.emitters[selectedLight];
    result.page = reference.page;
    result.emitter = reference.emitter;
    GridPage page = grid.pages[result.page];
    uint words = page.format == 3 ? 108 : (page.format == 2 ? 68 : 60);
    uint *light = page.emitters + result.emitter * words;
    float firstFraction = asfloat(light[3]);
    bool first = firstFraction == 1.0 || sample.x < firstFraction;
    float u = first ? sample.x / firstFraction : (sample.x - firstFraction) / (1.0 - firstFraction);
    SurfaceRecord emitter = loadQuad(light, page.format, first ? 0 : 1);
    float root = sqrt(u);
    float2 bary = float2(root * (1.0 - sample.y), root * sample.y);
    Triangle tri = emitter.triangle;
    // Grid pages contain static emitters with the same proven translation as closest hits.
    result.surface =
            reconstructStaticSurface(tri.p0.xyz, tri.p1.xyz, tri.p2.xyz, bary, page.origin.xyz);
    bool front = dot(result.surface.normal, receiver - result.surface.position) >= 0;
    emitter =
            selectMaterial(resources, emitter, front, bary, result.surface.position, coverageSeed);
    result.valid = acceptsCoverage(resources, emitter, bary, result.surface.position, coverageSeed);
    result.radiance =
            sourceEmission(resources, emitter, sourceSample(resources, emitter, bary, 0.0), front);
    result.areaPdf = selectionPdf * reference.inverseArea;
    result.twoSided = (emitter.identity.w & 2) != 0;
    return result;
}

public LightSample sampleLocalLight(TraceScene resources, ZSobol sampler, uint bounce,
                                    float3 receiver) {
    uint domain = 512 + 4 * bounce;
    // Two 2D domains share each index permutation without cascading one 24-bit residual.
    float4 selectors = float4(sampler.sample2D(domain), sampler.sample2D(768 + bounce));
    return sampleLocalLight(resources, selectors, sampler.sample2D(domain + 1),
                            sampler.bits1D(domain + 2), receiver);
}

public float emissiveHitPdf(TraceScene resources, SurfaceHit hit, float3 previous) {
    if (hit.light == 0xffffffffu)
        return 0;
    float3 delta = hit.surface.position - previous;
    float distanceSquared = dot(delta, delta);
    float cosine = distanceSquared > 0 ? abs(dot(hit.surface.normal, normalize(delta))) : 0;
    LightGrid grid = *((LightGrid *)resources.staticTrianglePages[0].lightGrid);
    float areaPdf = gridLightPdf(grid, previous, hit.light) * grid.emitters[hit.light].inverseArea;
    return primeAreaSolidAnglePdfValue(areaPdf, distanceSquared, cosine);
}

public float3 evaluateLocalLight(TraceScene resources, SurfacePoint receiver, LightSample light,
                                 uint coverageSeed, float4 medium, bool transmission,
                                 out float pdf) {
    pdf = 0;
    if (!light.valid)
        return float3(0);
    float3 delta = light.surface.position - receiver.position;
    float distanceSquared = dot(delta, delta);
    if (distanceSquared == 0)
        return float3(0);
    float3 direction = delta * rsqrt(distanceSquared);
    float cosine = transmission ? abs(dot(receiver.normal, direction))
                                : max(0.0, dot(receiver.normal, direction));
    float lightCosine = dot(light.surface.normal, -direction);
    if (light.twoSided)
        lightCosine = abs(lightCosine);
    if (cosine == 0 || lightCosine <= 0)
        return float3(0);
    pdf = primeAreaSolidAnglePdfValue(light.areaPdf, distanceSquared, lightCosine);
    float3 begin = spawnRay(receiver, direction);
    float3 end = spawnRay(light.surface, -direction);
    float3 segment = end - begin;
    float limit = length(segment);
    if (limit == 0)
        return float3(0);
    return traceTransmittance(resources, begin, segment / limit, coverageSeed, limit, medium) *
           light.radiance;
}
public float3 evaluateLocalLight(TraceScene resources, SurfacePoint receiver, LightSample light,
                                 uint coverageSeed, float4 medium, out float pdf) {
    return evaluateLocalLight(resources, receiver, light, coverageSeed, medium, false, pdf);
}
