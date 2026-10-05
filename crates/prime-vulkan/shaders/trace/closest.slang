#language slang 2026
module "trace/closest.slang";
import "math/ray_offset.slang";
import "math/color.slang";
import "math/rigid_motion.slang";
import "model/material/labpbr.slang";
import "model/material/medium.slang";
import "service/material/normal_mapping.slang";

// Vulkan pipeline specialization removes unused record/light paths before GPU execution.
// Test entry points use the complete semantics by default.
// 0: ordinary; 1: repeats/emission; 2: compound records and sprite sampling.
[[vk::constant_id(0)]] public const uint surfaceFeatures = 2;
[[vk::constant_id(2)]] public const uint opticalFeatures = 1;
[[vk::constant_id(3)]] public const uint motionFeatures = 0;

// GPU records are shared with Rust. Only entry points assign descriptor bindings.
public struct Triangle {
    public float4 p0;
    public float4 p1;
    public float4 p2;
    public float4 c0;
    public float4 c1;
    public float4 c2;
    public float4 uv01;
    public float2 uv2;
    public uint texture;
    public uint flags;
};
public struct InstanceMaterial {
    uint *quads;
    uint texture;
    public uint flags;
    uint tint;
    uint reserved0;
    uint reserved1;
    uint reserved2;
    float4 uv;
    float4 previous0;
    float4 previous1;
    float4 previous2;
};
public struct SurfaceRecord {
    public Triangle triangle;
    float4 uvU;
    float4 uvV;
    public float4 emission;
    public uint4 identity; // negative medium, positive medium, emitter, repeat/sided flags
    uint *compound;
    public uint mode;
    uint half;
    public float4 negative;
    public float4 positive;
    public uint positiveIorTexture;
};
public struct TrianglePage {
    uint *quads;
    uint format; // 0: ordinary 176B quad; 1: extended 240B surface
    public uint lightCount;
    public uint firstEmitter;
    uint reserved0;
    uint *emitters;
    public uint *lightGrid; // Opaque light directory; only the lighting module decodes it.
    uint reserved1;
    uint emitterFormat;
    public float4 origin;
};

public struct TraceScene {
#if !defined(PRIME_RESTIR_MATERIAL_ONLY)
    public RaytracingAccelerationStructure acceleration;
#endif
    public StructuredBuffer<uint4> textures;
    public StructuredBuffer<uint> texels;
    public StructuredBuffer<InstanceMaterial> instanceMaterials;
    public StructuredBuffer<TrianglePage> staticTrianglePages;
};
public struct SurfaceHit {
    public bool found;
    public bool staticGeometry; // Moving optical interface planes remain unsupported.
    public bool previousPositionKnown;
    public float3 previousPosition;
    public SurfacePoint surface;
    public float4 sourceColor;
    public float3 emission;
    public uint light;
    public float4 incident;
    public float4 transmitted;
    public bool optical;
    public bool thin;
    public bool materialThin;
    public bool physicalFront; // Original boundary orientation, before selecting a coating.
    public float3 shadingNormal;
    public float4 normalSample;
    public float4 specularSample;
    public uint materialFlags;
};
public float4 primeThinInsideMedium(SurfaceHit hit) {
    return primeThinInsideMedium(hit.physicalFront, hit.incident, hit.transmitted);
}
public float4 primeThinOutsideMedium(SurfaceHit hit) {
    return primeThinOutsideMedium(hit.physicalFront, hit.incident, hit.transmitted);
}
uint hash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352d;
    x ^= x >> 15;
    x *= 0x846ca68b;
    return x ^ (x >> 16);
}
float4 texel(StructuredBuffer<uint> texels, uint address) {
    uint packed = texels[address];
    return float4(packed & 255, (packed >> 8) & 255, (packed >> 16) & 255, packed >> 24) / 255.0;
}
struct TextureLevelSample {
    uint4 image;
    uint stride;
    uint next;
    float blend;
    bool sprite;
};
TextureLevelSample prepareTextureLevel(StructuredBuffer<uint4> textures, uint index, uint4 image,
                                       bool sprite) {
    TextureLevelSample result;
    result.image = image;
    result.sprite = sprite;
    result.stride = sprite ? result.image.w & 0x7fffffff : result.image.y;
    uint4 extra = sprite ? textures[index * 4 + 1] : uint4(0);
    result.next = extra.x;
    result.blend = asfloat(extra.w);
    return result;
}
float4 samplePreparedLevel(StructuredBuffer<uint> texels, TextureLevelSample level, float2 uv) {
    float2 local = level.sprite ? saturate(uv) : frac(uv);
    uint2 pixel = min(uint2(local * float2(level.image.yz)), level.image.yz - 1);
    uint at = pixel.y * level.stride + pixel.x;
    float4 color = texel(texels, level.image.x + at);
    if (level.sprite) {
        if (level.blend > 0.0) {
            float4 next = texel(texels, level.next + at);
            // MC 26.x interpolates all RGBA channels into an RGBA8_UNORM atlas.
            color = floor(lerp(color, next, level.blend) * 255.0 + 0.5) / 255.0;
        }
    }
    return color;
}
float4 sampleLevel(StructuredBuffer<uint4> textures, StructuredBuffer<uint> texels, uint index,
                   float2 uv, bool sprite) {
    return samplePreparedLevel(
            texels, prepareTextureLevel(textures, index, textures[index * 4], sprite), uv);
}
public float4 sampleTexture(StructuredBuffer<uint4> textures, StructuredBuffer<uint> texels,
                            uint texture, float2 uv, float lod) {
    uint4 image = textures[texture * 4];
    bool sprite = surfaceFeatures >= 2 && (image.w & 0x80000000) != 0;
    uint4 extra = sprite ? textures[texture * 4 + 1] : uint4(0);
    lod = clamp(lod, 0.0, float(extra.z));
    uint level = uint(lod);
    uint index = level == 0 ? texture : extra.y + level - 1;
    float4 color = sampleLevel(textures, texels, index, uv, sprite);
    float blend = frac(lod);
    if (blend > 0.0)
        color = lerp(color, sampleLevel(textures, texels, extra.y + level, uv, sprite), blend);
    return color;
}
public struct SourceSample {
    public float4 color;
    public float2 uv;
    public float2 du;
    public float2 dv;
    public float lod;
};
public SourceSample sourceSample(TraceScene resources, SurfaceRecord record, float2 bary,
                                 float footprint) {
    Triangle tri = record.triangle;
    float3 b = float3(1.0 - bary.x - bary.y, bary.x, bary.y);
    float2 uv = tri.uv01.xy * b.x + tri.uv01.zw * b.y + tri.uv2 * b.z;
    float2 du = tri.uv01.zw - tri.uv01.xy, dv = tri.uv2 - tri.uv01.xy;
    if ((record.identity.w & 1) != 0) {
        float2 tile = float2((record.identity.w & 4) != 0 ? frac(uv.x) : uv.x,
                             (record.identity.w & 8) != 0 ? frac(uv.y) : uv.y);
        uv = float2(dot(record.uvU.xy, tile) + record.uvU.z,
                    dot(record.uvV.xy, tile) + record.uvV.z);
        du = float2(dot(record.uvU.xy, du), dot(record.uvV.xy, du));
        dv = float2(dot(record.uvU.xy, dv), dot(record.uvV.xy, dv));
    }
    uint4 image = resources.textures[tri.texture * 4];
    float lod = 0.0;
    if (footprint > 0.0) {
        float3 e = tri.p1.xyz - tri.p0.xyz, f = tri.p2.xyz - tri.p0.xyz;
        float ee = dot(e, e), ef = dot(e, f), ff = dot(f, f);
        float inv = 1.0 / max(ee * ff - ef * ef, 1e-20);
        float3 gu = (e * (du.x * ff - dv.x * ef) + f * (dv.x * ee - du.x * ef)) * inv * image.y;
        float3 gv = (e * (du.y * ff - dv.y * ef) + f * (dv.y * ee - du.y * ef)) * inv * image.z;
        lod = max(log2(max(footprint * sqrt(max(dot(gu, gu), dot(gv, gv))), 1.0)), 0.0);
    }
    SourceSample result = {
            sampleTexture(resources.textures, resources.texels, tri.texture, uv, lod) *
                    (tri.c0 * b.x + tri.c1 * b.y + tri.c2 * b.z),
            uv, du, dv, lod};
    return result;
}
float4 sourceColor(TraceScene resources, SurfaceRecord record, float2 bary, float footprint) {
    return sourceSample(resources, record, bary, footprint).color;
}
float4 sourceColor(TraceScene resources, SurfaceRecord record, float2 bary) {
    // Coverage and emitter evaluation share the source base-level support domain.
    return sourceColor(resources, record, bary, 0.0);
}
public struct MaterialSample {
    public uint4 maps;
    public float2 uv;
};
public MaterialSample materialSource(TraceScene resources, uint texture, float2 uv) {
    uint4 maps = resources.textures[texture * 4 + 2];
    if ((maps.z & 8) != 0) {
        // Raw atlas coordinates use the base texture's repeat domain. Only the
        // resolved sprite coordinates clamp at their local frame endpoints.
        uv = frac(uv);
        uint4 lookup = resources.textures[maps.w * 4];
        uint2 pixel = min(uint2(uv * float2(lookup.yz)), lookup.yz - 1);
        uint sprite = resources.texels[lookup.x + pixel.y * lookup.y + pixel.x];
        if (sprite == 0) {
            MaterialSample empty = {uint4(0), uv};
            return empty;
        }
        float4 bounds = asfloat(resources.textures[sprite * 4 + 3]);
        uv = (uv - bounds.xy) / (bounds.zw - bounds.xy);
        maps = resources.textures[sprite * 4 + 2];
    }
    MaterialSample result = {maps, uv};
    return result;
}
// Continuous channels use their own mip chain. Fresnel and subsurface categories always
// retain the point-filtered base-level source identity, as in the original access service.
public float4 primeLinearMaterialLevel(StructuredBuffer<uint4> textures,
                                       StructuredBuffer<uint> texels, uint texture, float2 uv) {
    uint4 image = textures[texture * 4];
    bool sprite = surfaceFeatures >= 2 && (image.w & 0x80000000) != 0;
    TextureLevelSample level = prepareTextureLevel(textures, texture, image, sprite);
    float2 position = uv * float2(level.image.yz) - 0.5;
    float2 lo = floor(position);
    float2 f = frac(position);
    // Metadata/frame/stride are identical for all four neighbors. Preserve each original
    // normalized UV calculation and RGBA8 animation quantization before interpolation.
    float4 a = samplePreparedLevel(texels, level, (lo + float2(0.5, 0.5)) / float2(level.image.yz));
    float4 b = samplePreparedLevel(texels, level, (lo + float2(1.5, 0.5)) / float2(level.image.yz));
    float4 c = samplePreparedLevel(texels, level, (lo + float2(0.5, 1.5)) / float2(level.image.yz));
    float4 d = samplePreparedLevel(texels, level, (lo + float2(1.5, 1.5)) / float2(level.image.yz));
    return lerp(lerp(a, b, f.x), lerp(c, d, f.x), f.y);
}
float4 linearMaterialLevel(TraceScene resources, uint texture, float2 uv) {
    return primeLinearMaterialLevel(resources.textures, resources.texels, texture, uv);
}
public float4 linearMaterial(TraceScene resources, uint texture, float2 uv, float lod) {
    uint4 extra = resources.textures[texture * 4 + 1];
    lod = clamp(lod, 0.0, float(extra.z));
    uint level = uint(lod);
    uint index = level == 0 ? texture : extra.y + level - 1;
    float4 value = linearMaterialLevel(resources, index, uv);
    return frac(lod) == 0.0
                   ? value
                   : lerp(value, linearMaterialLevel(resources, extra.y + level, uv), frac(lod));
}
float3 mappedNormal(SurfaceRecord record, SourceSample source, float3 geometric, float4 sample) {
    float determinant = source.du.x * source.dv.y - source.du.y * source.dv.x;
    if (abs(determinant) <= 1e-20)
        return geometric;
    float3 e = record.triangle.p1.xyz - record.triangle.p0.xyz;
    float3 f = record.triangle.p2.xyz - record.triangle.p0.xyz;
    float3 tangent = (e * source.dv.y - f * source.du.y) / determinant;
    tangent -= geometric * dot(geometric, tangent);
    if (dot(tangent, tangent) <= 1e-20)
        return geometric;
    tangent = normalize(tangent);
    float3 bitangent = cross(geometric, tangent) * (determinant < 0.0 ? -1.0 : 1.0);
    float3 normal = primeAdaptLabPbrNormalAtlas(sample).tangentNormal;
    return normalize(tangent * normal.x + bitangent * normal.y + geometric * normal.z);
}
float3 sourceEmissionResolved(SurfaceRecord record, SourceSample source, bool front,
                              uint materialFlags, float emissionSample) {
    if (!front && (record.identity.w & 2) == 0)
        return float3(0);
    if ((materialFlags & 4) != 0) {
        float value = primeDecodeLabPbrEmission(emissionSample);
        return decodeSrgb(saturate(source.color.rgb)) * (1.5 * value);
    }
    return record.emission.rgb *
           ((record.identity.w & 16) != 0 ? decodeSrgb(saturate(source.color.rgb)) : float3(1));
}
public float3 sourceEmission(TraceScene resources, SurfaceRecord record, SourceSample source,
                             bool front) {
    // Generic emitter callers do not have the surface's filtered material sample.
    if (!front && (record.identity.w & 2) == 0)
        return float3(0);
    MaterialSample material = materialSource(resources, record.triangle.texture, source.uv);
    float emissionSample =
            (material.maps.z & 4) != 0
                    ? linearMaterial(resources, material.maps.y, material.uv, source.lod).a
                    : 0.0;
    return sourceEmissionResolved(record, source, front, material.maps.z, emissionSample);
}
public SurfaceRecord loadQuad(uint *base, uint format, uint primitive) {
    uint words = surfaceFeatures < 2 ? 44 + format * 16
                                     : (format == 3 ? 108 : (format == 2 ? 68 : 44 + format * 16));
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
    result.identity = uint4(0, 0, 0xffffffffu, 0);
    result.compound = record + 60;
    result.mode = surfaceFeatures >= 2 && format == 3 ? record[42] : 0;
    result.negative = result.positive = float4(1, 0, 0, 0);
    result.positiveIorTexture = 0;
    result.half = primitive & 1;
    if (surfaceFeatures != 0 && format != 0) {
        float4 *extra = (float4 *)(record + 44);
        result.uvU = extra[0];
        result.uvV = extra[1];
        result.emission = extra[2];
        result.identity = asuint(extra[3]);
        if (opticalFeatures != 0 && (result.identity.w & 32) != 0) {
            result.positiveIorTexture = asuint(extra[0].w);
            float4 *medium = (float4 *)(record + (format == 2 ? 60 : 100));
            result.negative = medium[0];
            result.positive = medium[1];
        }
    }
    return result;
}
public SurfaceRecord loadSurface(TraceScene resources, uint instance, uint geometry,
                                 uint primitive) {
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
public bool acceptsCoverage(TraceScene resources, SurfaceRecord record, float2 bary, float3 point,
                            uint raySeed) {
    if ((record.identity.w & 128) != 0)
        return true;
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

public SurfaceRecord secondary(SurfaceRecord base) {
    uint *data = base.compound;
    uint a = base.half != 0 ? 2 : 0, b = base.half != 0 ? 3 : 1, c = base.half != 0 ? 0 : 2;
    float4 *colors = (float4 *)data;
    float2 *uvs = (float2 *)(data + 16);
    base.triangle.c0 = colors[a];
    base.triangle.c1 = colors[b];
    base.triangle.c2 = colors[c];
    base.triangle.uv01 = float4(uvs[a], uvs[b]);
    base.triangle.uv2 = uvs[c];
    base.triangle.texture = data[24];
    base.triangle.flags = data[25];
    base.identity.w = data[26] | (base.identity.w & 224);
    base.uvU = ((float4 *)data)[7];
    base.uvV = ((float4 *)data)[8];
    base.emission.rgb = ((float4 *)data)[9].rgb;
    return base;
}
public SurfaceRecord boundaryIors(TraceScene resources, SurfaceRecord record, float2 bary,
                                  float footprint) {
    if (opticalFeatures == 0)
        return record;
    // Reference coordinates stay fixed while their G category follows the current frame.
    // Coatings cannot replace either physical medium's source field.
    if (record.positiveIorTexture != 0) {
        MaterialSample reference =
                materialSource(resources, record.positiveIorTexture, float2(0.5));
        if ((reference.maps.z & 2) != 0) {
            float g = sampleTexture(resources.textures, resources.texels, reference.maps.y,
                                    reference.uv, 0.0)
                              .g;
            record.positive.x = primePbrFresnelCodeIor(uint(round(g * 255.0)));
        }
    }
    if ((record.identity.x & PRIME_PBR_DYNAMIC_MEDIUM_IOR) != 0) {
        SourceSample source = sourceSample(resources, record, bary, footprint);
        MaterialSample material = materialSource(resources, record.triangle.texture, source.uv);
        if ((material.maps.z & 2) != 0) {
            float g = sampleTexture(resources.textures, resources.texels, material.maps.y,
                                    material.uv, 0.0)
                              .g;
            record.negative.x = primePbrFresnelCodeIor(uint(round(g * 255.0)));
        }
    }
    return record;
}
public SurfaceRecord selectMaterial(TraceScene resources, SurfaceRecord record, bool front,
                                    float2 bary, float3 point, uint seed,
                                    out uint selectedMaterial) {
    selectedMaterial = 0;
    if (record.mode == 1) {
        if (!front) {
            record = secondary(record);
            record.identity.w |= 2;
            selectedMaterial = 1;
        }
    } else if (record.mode == 3 || (record.mode == 2 && front)) {
        SurfaceRecord top = secondary(record);
        top.identity.w &= ~128u; // The coating is an actual material, with its own coverage.
        if (acceptsCoverage(resources, top, bary, point, seed)) {
            record = top;
            selectedMaterial = 1;
        }
    }
    return record;
}

public SurfaceRecord selectMaterial(TraceScene resources, SurfaceRecord record, bool front,
                                    float2 bary, float3 point, uint seed) {
    uint selectedMaterial;
    return selectMaterial(resources, record, front, bary, point, seed, selectedMaterial);
}

// Reuse exactly the closest-hit material producer when a renderer reconstructs an owned hit.
// Instance transforms and the physical surface are supplied by that renderer's scene adapter.
public SurfaceHit resolveSurfaceHit(TraceScene resources, SurfaceRecord record, uint instance,
                                    uint geometry, uint primitive, float2 bary, bool front,
                                    SurfacePoint surface, float3x4 transform, uint coverageSeed,
                                    float3 direction, float footprint, float3 coveragePoint,
                                    uint forcedMaterial, out uint selectedMaterial) {
    SurfaceHit hit = {};
    hit.found = true;
    hit.surface = surface;
    SurfaceRecord boundary = record;
    Triangle tri = record.triangle;
    if (forcedMaterial == 0xffffffffu || record.mode == 1) {
        record = selectMaterial(resources, record, front, bary, coveragePoint, coverageSeed,
                                selectedMaterial);
    } else {
        selectedMaterial = forcedMaterial;
        if (forcedMaterial != 0) {
            record = secondary(record);
            if (record.mode == 1 && !front)
                record.identity.w |= 2;
            else
                record.identity.w &= ~128u;
        }
    }
    if ((instance & 0x00800000) != 0) {
        record.triangle.p0.xyz = mul(transform, float4(tri.p0.xyz, 1));
        record.triangle.p1.xyz = mul(transform, float4(tri.p1.xyz, 1));
        record.triangle.p2.xyz = mul(transform, float4(tri.p2.xyz, 1));
        boundary.triangle.p0.xyz = record.triangle.p0.xyz;
        boundary.triangle.p1.xyz = record.triangle.p1.xyz;
        boundary.triangle.p2.xyz = record.triangle.p2.xyz;
    }
    hit.previousPosition = hit.surface.position;
    hit.previousPositionKnown = (instance & 0x00800000) == 0;
    if (motionFeatures != 0 && !hit.previousPositionKnown) {
        InstanceMaterial placement = resources.instanceMaterials[instance & 0x007fffff];
        if ((placement.reserved0 & 1u) != 0) {
            hit.previousPosition = primeRigidPreviousPosition(
                    tri.p0.xyz, tri.p1.xyz, tri.p2.xyz, bary,
                    float3x4(placement.previous0, placement.previous1, placement.previous2));
            hit.previousPositionKnown = true;
        }
    }
    boundary = boundaryIors(resources, boundary, bary, footprint);
    record.negative = boundary.negative;
    record.positive = boundary.positive;
    SourceSample sampled = sourceSample(resources, record, bary, footprint);
    hit.sourceColor = sampled.color;
    MaterialSample material = materialSource(resources, record.triangle.texture, sampled.uv);
    hit.materialFlags = material.maps.z;
    uint4 maps = material.maps;
    hit.normalSample = (hit.materialFlags & 1) != 0
                               ? linearMaterial(resources, maps.x, material.uv, sampled.lod)
                               : float4(0.5, 0.5, 1, 0);
    hit.specularSample =
            (hit.materialFlags & 2) != 0
                    ? sampleTexture(resources.textures, resources.texels, maps.y, material.uv, 0.0)
                    : float4(0);
    if ((hit.materialFlags & 2) != 0) {
        float4 continuous = linearMaterial(resources, maps.y, material.uv, sampled.lod);
        hit.specularSample.r = continuous.r;
        hit.specularSample.a = continuous.a;
    }
    hit.shadingNormal =
            (hit.materialFlags & 1) != 0
                    ? mappedNormal(record, sampled, hit.surface.normal, hit.normalSample)
                    : hit.surface.normal;
    if ((hit.materialFlags & 1) != 0)
        hit.shadingNormal = primeEnsureValidNormalMappedReflection(hit.surface.normal, -direction,
                                                                   hit.shadingNormal);
    // Resident metadata guarantees authored emission has a specular map. Reuse the same
    // selected shading record, animation frame, UV, LOD and filtered A already read above.
    hit.emission = sourceEmissionResolved(record, sampled, dot(hit.surface.normal, direction) < 0,
                                          hit.materialFlags, hit.specularSample.a);
    hit.light = (instance & 0x00800000) == 0 && record.identity.z != 0xffffffffu
                        ? resources.staticTrianglePages[instance + geometry].firstEmitter +
                                  record.identity.z
                        : 0xffffffffu;
    hit.incident = front ? record.positive : record.negative;
    hit.transmitted = front ? record.negative : record.positive;
    hit.physicalFront = front;
    hit.optical = opticalFeatures != 0 && (record.identity.w & 128) != 0;
    hit.thin = opticalFeatures != 0 && (record.identity.w & 64) != 0;
    hit.materialThin = primePbrSourceThinWalled(hit.thin, record.triangle.flags,
                                                uint(round(hit.specularSample.b * 255.0)));
    hit.staticGeometry = (instance & 0x00800000) == 0;
    return hit;
}

// The ordinary PT uses the empty observer; it carries no extra identity through a query.
public interface IClosestHitIdentity {
    [mutating] void capture(uint instance, uint geometry, uint primitive, float2 bary,
                            uint selectedMaterial);
}
public struct NoClosestHitIdentity : IClosestHitIdentity {
    [mutating] public void capture(uint instance, uint geometry, uint primitive, float2 bary,
                                   uint selectedMaterial) {}
};

#if !defined(PRIME_RESTIR_MATERIAL_ONLY)
public SurfaceHit traceClosest<T : IClosestHitIdentity>(TraceScene resources, float3 origin,
                                                        float3 direction, uint coverageSeed,
                                                        float coneWidth, float coneSpread,
                                                        inout T identity) {
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
            candidate = selectMaterial(resources, candidate, query.CandidateTriangleFrontFace(),
                                       query.CandidateTriangleBarycentrics(),
                                       origin + direction * query.CandidateTriangleRayT(),
                                       coverageSeed);
            if (acceptsCoverage(resources, candidate, query.CandidateTriangleBarycentrics(),
                                origin + direction * query.CandidateTriangleRayT(), coverageSeed))
                query.CommitNonOpaqueTriangleHit();
        }
    }
    // Miss members have no consumers; define them so Slang cannot carry a prior vertex's
    // uninitialized return members through the bounce loop and closest query.
    SurfaceHit hit = {};
    hit.found = query.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
    if (hit.found) {
        uint instance = query.CommittedInstanceID();
        uint geometry = query.CommittedGeometryIndex();
        uint primitive = query.CommittedPrimitiveIndex();
        float2 bary = query.CommittedTriangleBarycentrics();
        bool front = query.CommittedTriangleFrontFace();
        SurfaceRecord record = loadSurface(resources, instance, geometry, primitive);
        Triangle tri = record.triangle;
        float3x4 transform = float3x4(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
        SurfacePoint surface;
        if ((instance & 0x00800000) == 0) {
            surface = reconstructStaticSurface(
                    tri.p0.xyz, tri.p1.xyz, tri.p2.xyz, bary,
                    resources.staticTrianglePages[instance + geometry].origin.xyz);
        } else {
            transform = query.CommittedObjectToWorld3x4();
            surface = reconstructSurface(tri.p0.xyz, tri.p1.xyz, tri.p2.xyz, bary, transform,
                                         query.CommittedWorldToObject3x4());
        }
        float footprint = (coneWidth + coneSpread * query.CommittedRayT()) /
                          max(abs(dot(surface.normal, direction)), 0.05);
        uint selectedMaterial;
        hit = resolveSurfaceHit(resources, record, instance, geometry, primitive, bary, front,
                                surface, transform, coverageSeed, direction, footprint,
                                origin + direction * query.CommittedRayT(), 0xffffffffu,
                                selectedMaterial);
        identity.capture(instance, geometry, primitive, bary, selectedMaterial);
    }
    return hit;
}
public SurfaceHit traceClosest(TraceScene resources, float3 origin, float3 direction,
                               uint coverageSeed, float coneWidth, float coneSpread) {
    NoClosestHitIdentity identity;
    return traceClosest(resources, origin, direction, coverageSeed, coneWidth, coneSpread,
                        identity);
}
public SurfaceHit traceClosest(TraceScene resources, float3 origin, float3 direction,
                               uint coverageSeed) {
    return traceClosest(resources, origin, direction, coverageSeed, 0.0, 0.0);
}
#endif
