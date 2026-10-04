// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "restir/scene.slang";

import "frame.slang";
#if defined(PRIME_RESTIR_MATERIAL_ONLY)
import "trace/closest.slang";
#else
import "ray_query.slang";
#endif
import "pbr.slang";
import "bsdf/full/energy.slang";
import "math/color.slang";
import "math/ray_offset.slang";
import "math/transport.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "atmosphere/view.slang";
import "restir/reservoir.slang";
import "restir/rng.slang";
import "restir/identity.slang";
__exported import "restir/types.slang";
#if defined(PRIME_LIGHT_TREE_SPHERE)
import "sphere_tree_sampling.slang";
#else
import "tree_sampling.slang";
#endif

public static const uint RESTIR_LIGHT_SKY = 0;
public static const uint RESTIR_LIGHT_EMISSIVE = 1;
public static const uint RESTIR_LIGHT_SUN = 2;
public static const uint RESTIR_EMITTER_PAGE = 0x40000000u;
public static const uint RESTIR_MAX_RC_LENGTH = 255;
public static const float RESTIR_DIFFUSE_PSEUDO_PDF = 0.15915494309189533577;
public static const float RESTIR_DISTANCE_THRESHOLD = 0.0002;
public static const float RESTIR_DISTANCE_SIGMA = 0.2;
public static const float RESTIR_ROUGHNESS_THRESHOLD = 0.2;

public struct RestirScene {
    public TraceScene resources;
    public AtmEnvironment environment;
    public PrimeFullTransmissionGgxEnergy energy;
    public Frame frame;
    public uint *tlasInstances;
    public uint terrainCount;
    public float2 sampleJitter;
    public uint2 *historyStatic;
    public uint2 *historyDynamic;
    public uint2 *historyEmitters;
    public uint4 identityControl;
};

public bool restirHistoryHitValid(RestirScene scene, HitInfo hit) {
    return restirHistoryHitValid(hit, scene.historyStatic, scene.historyDynamic,
                                 scene.historyEmitters, scene.identityControl);
}

#if !defined(PRIME_RESTIR_MATERIAL_ONLY)
public struct RestirHitIdentity : IClosestHitIdentity {
    public HitInfo hit;
    [mutating] public void capture(uint instance, uint geometry, uint primitive, float2 bary,
                                   uint selectedMaterial) {
        hit = HitInfo((instance & 0x00800000u) != 0 ? instance : instance + geometry, primitive,
                      bary);
        hit.data.x |= selectedMaterial << 24;
    }
};
#endif

public float3 restirPrimaryDirection(Frame frame, uint2 pixel, float2 jitter = float2(0)) {
    float2 screen = ((float2(pixel) + 0.5 + jitter) / float2(frame.dimensions.xy)) * 2.0 - 1.0;
    return normalize(frame.forward.xyz +
                     frame.right.xyz * (screen.x * frame.forward.w * frame.position.w) -
                     frame.up.xyz * (screen.y * frame.position.w));
}

float3x4 restirInverseTransform(float3x4 transform) {
    float3 a = transform[0].xyz, b = transform[1].xyz, c = transform[2].xyz;
    float3 ca = cross(b, c), cb = cross(c, a), cc = cross(a, b);
    float inverseDet = 1.0 / dot(a, ca);
    float3x3 inverse = transpose(float3x3(ca, cb, cc)) * inverseDet;
    float3 translation = -mul(inverse, float3(transform[0].w, transform[1].w, transform[2].w));
    return float3x4(float4(inverse[0], translation.x), float4(inverse[1], translation.y),
                    float4(inverse[2], translation.z));
}

float3x4 restirDynamicTransform(RestirScene scene, uint instance) {
    uint index = scene.terrainCount + (instance & 0x007fffffu);
    uint *words = scene.tlasInstances + 16 * index;
    return float3x4(asfloat(uint4(words[0], words[1], words[2], words[3])),
                    asfloat(uint4(words[4], words[5], words[6], words[7])),
                    asfloat(uint4(words[8], words[9], words[10], words[11])));
}

// NEE emitter identities address the immutable emitter directory, rather than searching
// terrain for a matching source quad. Only terminal emitters use this identity category.
public SurfaceHit restirLoadEmitter(RestirScene scene, HitInfo identity, float3 view,
                                    uint coverageSeed) {
    uint pageIndex = identity.pageID() & 0x00ffffffu;
    uint primitive = identity.primitiveIndex();
    LightTree tree = *((LightTree *)scene.resources.staticTrianglePages[0].lightGrid);
    TreePage page = tree.pages[pageIndex];
    uint words = page.format == 3 ? 108 : (page.format == 2 ? 68 : 60);
    SurfaceRecord record =
            loadQuad(page.emitters + (primitive >> 1) * words, page.format, primitive & 1u);
    Triangle triangle = record.triangle;
    SurfaceHit hit = {};
    hit.found = true;
    hit.staticGeometry = true;
    hit.surface = reconstructStaticSurface(triangle.p0.xyz, triangle.p1.xyz, triangle.p2.xyz,
                                           identity.barycentrics(), page.origin.xyz);
    bool front = dot(hit.surface.normal, view) >= 0.0;
    if (record.mode == 1) {
        record = selectMaterial(scene.resources, record, front, identity.barycentrics(),
                                hit.surface.position, coverageSeed);
    } else if (((identity.pageID() >> 24) & 1u) != 0) {
        record = secondary(record);
        record.identity.w &= ~128u;
    }
    hit.emission = sourceEmission(scene.resources, record,
                                  sourceSample(scene.resources, record, identity.barycentrics(), 0),
                                  front);
    hit.light = page.first + (primitive >> 1);
    hit.incident = front ? record.positive : record.negative;
    hit.transmitted = front ? record.negative : record.positive;
    hit.shadingNormal = hit.surface.normal;
    return hit;
}

public SurfaceHit restirLoadHit(RestirScene scene, HitInfo identity, float3 view,
                                uint coverageSeed = 0, float footprint = 0) {
    if (!identity.isValid()) {
        SurfaceHit empty = {};
        return empty;
    }
    if ((identity.pageID() & RESTIR_EMITTER_PAGE) != 0)
        return restirLoadEmitter(scene, identity, view, coverageSeed);
    uint instance = identity.pageID() & 0x00ffffffu;
    SurfaceRecord record = loadSurface(scene.resources, instance, 0, identity.primitiveIndex());
    Triangle triangle = record.triangle;
    float3x4 transform = float3x4(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
    SurfacePoint surface;
    if ((instance & 0x00800000u) == 0) {
        surface = reconstructStaticSurface(
                triangle.p0.xyz, triangle.p1.xyz, triangle.p2.xyz, identity.barycentrics(),
                scene.resources.staticTrianglePages[instance].origin.xyz);
    } else {
        transform = restirDynamicTransform(scene, instance);
        surface = reconstructSurface(triangle.p0.xyz, triangle.p1.xyz, triangle.p2.xyz,
                                     identity.barycentrics(), transform,
                                     restirInverseTransform(transform));
    }
    uint selectedMaterial;
    return resolveSurfaceHit(scene.resources, record, instance, 0, identity.primitiveIndex(),
                             identity.barycentrics(), dot(surface.normal, view) >= 0.0, surface,
                             transform, coverageSeed, -view, footprint, surface.position,
                             (identity.pageID() >> 24) & 1u, selectedMaterial);
}

#if !defined(PRIME_RESTIR_MATERIAL_ONLY)
public SurfaceHit restirTrace(RestirScene scene, float3 origin, float3 direction, uint coverageSeed,
                              out HitInfo identity) {
    RestirHitIdentity observer;
    observer.hit = HitInfo();
    SurfaceHit hit = traceClosest(scene.resources, origin, direction, coverageSeed, 0, 0, observer);
    identity = observer.hit;
    return hit;
}
#endif

public SurfacePoint restirHitSurface(RestirScene scene, HitInfo identity) {
    uint pageID = identity.pageID() & 0x00ffffffu;
    uint primitive = identity.primitiveIndex();
    if ((identity.pageID() & RESTIR_EMITTER_PAGE) != 0) {
        LightTree tree = *((LightTree *)scene.resources.staticTrianglePages[0].lightGrid);
        TreePage page = tree.pages[pageID];
        uint words = page.format == 3 ? 108 : (page.format == 2 ? 68 : 60);
        Triangle tri =
                loadQuad(page.emitters + (primitive >> 1) * words, page.format, primitive & 1u)
                        .triangle;
        return reconstructStaticSurface(tri.p0.xyz, tri.p1.xyz, tri.p2.xyz, identity.barycentrics(),
                                        page.origin.xyz);
    }
    Triangle tri = loadSurface(scene.resources, pageID, 0, primitive).triangle;
    if ((pageID & 0x00800000u) == 0)
        return reconstructStaticSurface(tri.p0.xyz, tri.p1.xyz, tri.p2.xyz, identity.barycentrics(),
                                        scene.resources.staticTrianglePages[pageID].origin.xyz);
    float3x4 transform = restirDynamicTransform(scene, pageID);
    return reconstructSurface(tri.p0.xyz, tri.p1.xyz, tri.p2.xyz, identity.barycentrics(),
                              transform, restirInverseTransform(transform));
}

public PrimePbrVertex restirMaterial(SurfaceHit hit, float3 view) {
    return primePbrVertex(sourceToWorking(saturate(hit.sourceColor.rgb)), hit.shadingNormal,
                          hit.normalSample.a, hit.specularSample.rgb, (hit.materialFlags & 1u) != 0,
                          (hit.materialFlags & 2u) != 0, hit.materialThin, hit.optical, view);
}

public float restirPrimaryFootprint(RestirScene scene, SurfaceHit hit, float3 view) {
    float3 displacement = scene.frame.position.xyz - hit.surface.position;
    return dot(displacement, displacement) /
           abs(0.079577471545947667884 * dot(view, hit.surface.normal));
}

// Falcor's MaterialInstanceBase contract for materials without component indexing.
public bool restirSampleRough(float samplePdf, float roughnessThreshold) {
    return samplePdf <= 1.0 / (roughnessThreshold * roughnessThreshold);
}
public bool restirNeeMayReconnectAtSurface(SurfaceHit hit) {
#if defined(PRIME_LIGHT_TREE_SPHERE)
    // The sphere proposal depends on the view-adjusted shading normal. Keep
    // such a terminal NEE sample as its emitter endpoint, whose PMF is re-evaluated.
    return hit.optical || (hit.materialFlags & 1u) == 0;
#else
    // TREE depends on the preserved RC position, not its changed incident view.
    return true;
#endif
}
public void restirSampleThresholds(inout SampleGenerator sg, out float distanceThreshold,
                                   out float roughnessThreshold) {
    distanceThreshold =
            PathRISState::gaussrand(RESTIR_DISTANCE_THRESHOLD, RESTIR_DISTANCE_SIGMA, sg);
    roughnessThreshold = RESTIR_ROUGHNESS_THRESHOLD;
}

public float4 restirIncident(SurfaceHit hit, float4 medium) {
    return hit.optical && !hit.thin ? hit.incident : medium;
}
public float4 restirTransmitted(SurfaceHit hit) {
    return hit.thin ? (hit.incident.x > hit.transmitted.x ? hit.incident : hit.transmitted)
                    : hit.transmitted;
}
#if !defined(PRIME_RESTIR_MATERIAL_ONLY)
public BsdfEvaluation restirEvaluate(RestirScene scene, SurfaceHit hit, float3 view,
                                     float3 direction, float4 medium) {
    PrimePbrVertex material = restirMaterial(hit, view);
    BsdfEvaluation evaluated =
            primeEvaluatePbrVertex(scene.energy, material, view, direction,
                                   restirIncident(hit, medium), restirTransmitted(hit));
    if (!primeGeometricEvaluationSupport(primePbrVertexHasNormal(material), hit.surface.normal,
                                         material.normal, view, direction, hit.optical))
        return primeInvalidBsdfEvaluation();
    return evaluated;
}

public PrimePbrSample restirScatter(RestirScene scene, SurfaceHit hit, float3 view, float4 medium,
                                    inout SampleGenerator sg) {
    PrimePbrVertex material = restirMaterial(hit, view);
    PrimePbrSample result =
            primeSamplePbrVertex(scene.energy, material, view, sampleNext3D(sg),
                                 restirIncident(hit, medium), restirTransmitted(hit));
    if (!primeGeometricSampleSupport(primePbrVertexHasNormal(material), hit.surface.normal, view,
                                     result.bsdfSample))
        result.bsdfSample = primeInvalidBsdfSample();
    return result;
}

public float restirLocalSelectionProbability(RestirScene scene) {
    return lightFeatures != 0 && hasLocalLights(scene.resources) ? 0.5 : 0.0;
}
public float restirEmitterPdf(RestirScene scene, SurfaceHit emitter, float3 receiver,
                              float3 proposalNormal) {
    return restirLocalSelectionProbability(scene) *
           emissiveHitPdf(scene.resources, emitter, receiver, proposalNormal);
}
public float restirEnvironmentPdf(RestirScene scene, uint type, float3 direction) {
    return type == RESTIR_LIGHT_SUN && atmSunContains(scene.environment, direction)
                   ? (1.0 - restirLocalSelectionProbability(scene)) * atmSunPdf()
                   : 0.0;
}
public float3 restirEnvironmentRadiance(RestirScene scene, uint type, float3 direction) {
    return type == RESTIR_LIGHT_SUN
                   ? (atmSunContains(scene.environment, direction)
                              ? atmSunRadiance(scene.environment, direction) * scene.frame.right.w
                              : float3(0))
                   : atmSky(scene.environment, direction, scene.frame.up.w);
}

public struct RestirLightSample {
    public float3 direction;
    public float3 Le;
    public float3 visibility;
    public float pdf;
    public float geometry;
    public HitInfo hit;
    public uint type;
};

// Fixed 1 + 4 + 2 dimensions even for rejected/no-NEE proposals. Coverage is a
// separate position-hashed source field keyed by the path seed, with no variable RNG use.
public void restirSkipLightSample(inout SampleGenerator sg) {
    sampleNext1D(sg);
    sampleNext4D(sg);
    sampleNext2D(sg);
}
public RestirLightSample restirSampleLight(RestirScene scene, SurfaceHit receiver, float3 view,
                                           float4 medium, uint coverageSeed,
                                           inout SampleGenerator sg) {
    float selector = sampleNext1D(sg);
    float4 selectors = sampleNext4D(sg);
    float2 sample = sampleNext2D(sg);
    RestirLightSample result = {};
    result.hit = HitInfo();
    float localProbability = restirLocalSelectionProbability(scene);
    if (selector < localProbability) {
        PrimePbrVertex material = restirMaterial(receiver, view);
        float3 proposalNormal = receiver.optical ? float3(0) : material.normal;
        LightSample light = sampleLocalLight(scene.resources, selectors, sample, coverageSeed,
                                             receiver.surface.position, proposalNormal);
        if (!light.valid)
            return result;
        float3 delta = light.surface.position - receiver.surface.position;
        float distanceSquared = dot(delta, delta);
        if (!(distanceSquared > 0))
            return result;
        result.direction = delta * rsqrt(distanceSquared);
        float cosine = light.twoSided ? abs(dot(light.surface.normal, -result.direction))
                                      : max(0.0, dot(light.surface.normal, -result.direction));
        result.geometry = cosine / distanceSquared;
        float4 shadowMedium = receiver.optical && !receiver.thin &&
                                              dot(receiver.surface.normal, result.direction) *
                                                              dot(receiver.surface.normal, view) <
                                                      0
                                      ? receiver.transmitted
                                      : medium;
        float pdf;
        result.Le = linear709ToWorking(light.radiance);
        SurfacePoint shadowReceiver = receiver.surface;
        if (dot(shadowReceiver.normal, view) < 0)
            shadowReceiver.normal = -shadowReceiver.normal;
        float3 attenuated = evaluateLocalLight(scene.resources, shadowReceiver, light, coverageSeed,
                                               shadowMedium, receiver.optical, pdf);
        result.visibility =
                float3(result.Le.x > 0 ? linear709ToWorking(attenuated).x / result.Le.x : 1,
                       result.Le.y > 0 ? linear709ToWorking(attenuated).y / result.Le.y : 1,
                       result.Le.z > 0 ? linear709ToWorking(attenuated).z / result.Le.z : 1);
        result.pdf = pdf * localProbability;
        result.type = RESTIR_LIGHT_EMISSIVE;
        // Recreate the selected triangle's barycentric sample exactly as sampleLocalLight.
        LightTree tree = *((LightTree *)scene.resources.staticTrianglePages[0].lightGrid);
        TreePage page = tree.pages[light.page];
        uint words = page.format == 3 ? 108 : (page.format == 2 ? 68 : 60);
        float fraction = asfloat(page.emitters[light.emitter * words + 3]);
        bool first = fraction == 1.0 || sample.x < fraction;
        float u = first ? sample.x / fraction : (sample.x - fraction) / (1.0 - fraction);
        float root = sqrt(u);
        result.hit = HitInfo(RESTIR_EMITTER_PAGE | light.page, 2 * light.emitter + uint(!first),
                             float2(root * (1.0 - sample.y), root * sample.y));
        uint selectedMaterial;
        SurfaceRecord record =
                loadQuad(page.emitters + light.emitter * words, page.format, uint(!first));
        selectMaterial(scene.resources, record, dot(light.surface.normal, -result.direction) >= 0,
                       result.hit.barycentrics(), light.surface.position, coverageSeed,
                       selectedMaterial);
        result.hit.data.x |= selectedMaterial << 24;
    } else {
        result.direction = atmSampleSun(scene.environment, sample);
        result.Le = atmSunRadiance(scene.environment, result.direction) * scene.frame.right.w;
        result.pdf = (1.0 - localProbability) * atmSunPdf();
        result.geometry = 1;
        result.type = RESTIR_LIGHT_SUN;
        if (all(result.Le == 0))
            return result;
        float4 shadowMedium = receiver.optical && !receiver.thin &&
                                              dot(receiver.surface.normal, result.direction) *
                                                              dot(receiver.surface.normal, view) <
                                                      0
                                      ? receiver.transmitted
                                      : medium;
        result.visibility =
                traceTransmittance(scene.resources, spawnRay(receiver.surface, result.direction),
                                   result.direction, coverageSeed, 100000, shadowMedium);
    }
    return result;
}

#endif

public float3 restirApplyAtmosphere(RestirScene scene, uint2 pixel, RestirPrimary primary,
                                    float3 radiance) {
    if (!primary.hit.isValid())
        return radiance;
    SurfaceHit hit = restirLoadHit(scene, primary.hit,
                                   -restirPrimaryDirection(scene.frame, pixel, scene.sampleJitter),
                                   primary.coverageSeed);
    if (hit.incident.x != 1.0 && !hit.thin)
        return radiance;
    float3 s, t;
    atmAerial(scene.environment, (float2(pixel) + 0.5) / float2(scene.frame.dimensions.xy),
              length(hit.surface.position - scene.frame.position.xyz), s, t);
    return t * radiance + s * scene.frame.right.w;
}
