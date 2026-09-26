// GPU ABI is packed explicitly in geometry.rs/dynamic.rs; colors remain source-encoded
// until texture * interpolated tint has been evaluated at the hit point.
struct Triangle {
    float4 p0; float4 p1; float4 p2;
    float4 c0; float4 c1; float4 c2;
    float4 uv01; float2 uv2; uint texture; uint flags;
};
struct Frame {
    float4 position; // w = tan(vertical FOV / 2)
    float4 forward;  // w = aspect ratio
    float4 right;
    float4 up;
    uint4 dimensions; // width, height, sample index, triangle count
    uint4 target; // x: host bottom-up; y: RNG sample independent of accumulation reset
};
[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
[[vk::binding(1, 0)]] StructuredBuffer<Triangle> triangles;
[[vk::binding(2, 0)]] StructuredBuffer<uint4> textures; // offset, width, height, reserved
[[vk::binding(3, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;
[[vk::binding(5, 0)]] RWStructuredBuffer<float4> accumulation;
[[vk::binding(6, 0)]] StructuredBuffer<Triangle> dynamicTriangles;
struct InstanceMaterial { uint first; uint texture; uint flags; uint tint; float4 uv; };
[[vk::binding(7, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::push_constant]] ConstantBuffer<Frame> frame;

uint hash(uint x) {
    x ^= x >> 16; x *= 0x7feb352d; x ^= x >> 15; x *= 0x846ca68b; return x ^ (x >> 16);
}
// Advance before hashing: hash(0)==0 must not become an absorbing random stream.
float random(inout uint state) { state = hash(state + 0x9e3779b9u); return float(state >> 8) * (1.0 / 16777216.0); }
float4 sourceColor(Triangle tri, float2 bary) {
    float3 b = float3(1.0 - bary.x - bary.y, bary.x, bary.y);
    float2 uv = tri.uv01.xy * b.x + tri.uv01.zw * b.y + tri.uv2 * b.z;
    uint4 texture = textures[tri.texture];
    uint2 pixel = min(uint2(frac(uv) * float2(texture.yz)), texture.yz - 1);
    uint packed = texels[texture.x + pixel.y * texture.y + pixel.x];
    float4 texel = float4(packed & 255, (packed >> 8) & 255, (packed >> 16) & 255, packed >> 24) / 255.0;
    return texel * (tri.c0 * b.x + tri.c1 * b.y + tri.c2 * b.z);
}
float3 linearColor(float3 encoded) {
    return select(encoded <= 0.04045, encoded / 12.92, pow((encoded + 0.055) / 1.055, 2.4));
}
float3 displayColor(float3 linear) {
    float3 mapped = linear / (1.0 + linear); // explicit Reinhard exposure/display MVP
    return select(mapped <= 0.0031308, mapped * 12.92, 1.055 * pow(mapped, 1.0 / 2.4) - 0.055);
}
Triangle loadTriangle(uint instance, uint primitive) {
    if ((instance & 0x00800000) == 0) return triangles[instance + primitive];
    InstanceMaterial material = instanceMaterials[instance & 0x007fffff];
    Triangle tri = dynamicTriangles[material.first + primitive];
    if (material.texture != 0xffffffff) tri.texture = material.texture;
    if (material.flags != 0xffffffff) tri.flags = material.flags;
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
bool acceptsCoverage(Triangle tri, float2 bary, float3 point, uint raySeed) {
    if (tri.flags == 0) return true;
    float alpha = saturate(sourceColor(tri, bary).a);
    if (tri.flags == 1) return alpha >= 0.1;
    // Alpha blend is stochastic coverage, not refraction/absorption. Quantized
    // coincident hits share one event, including emitted front/back fluid faces.
    // Intentionally correlates distinct surfaces at the same quantized position.
    uint3 cell = asuint(int3(floor(point * 256.0 + 0.5)));
    uint event = hash(raySeed ^ hash(cell.x) ^ hash(cell.y + 0x9e3779b9) ^ hash(cell.z + 0x85ebca6b));
    return float(event >> 8) * (1.0 / 16777216.0) < alpha;
}
struct Hit { uint instance; uint primitive; float2 bary; float3x4 objectToWorld; bool found; };
Hit trace(float3 origin, float3 direction, inout uint state) {
    state = hash(state + 0x9e3779b9u);
    RayDesc ray; ray.Origin = origin; ray.Direction = direction; ray.TMin = 0.0; ray.TMax = 100000.0;
    RayQuery<RAY_FLAG_NONE> query;
    query.TraceRayInline(scene, RAY_FLAG_NONE, 0xff, ray);
    while (query.Proceed()) {
        if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
            Triangle candidate = loadTriangle(query.CandidateInstanceID(), query.CandidatePrimitiveIndex());
            if (acceptsCoverage(candidate, query.CandidateTriangleBarycentrics(),
                    origin + direction * query.CandidateTriangleRayT(), state))
                query.CommitNonOpaqueTriangleHit();
        }
    }
    Hit hit; hit.found = query.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
    hit.instance = hit.found ? query.CommittedInstanceID() : 0;
    hit.primitive = hit.found ? query.CommittedPrimitiveIndex() : 0;
    hit.bary = hit.found ? query.CommittedTriangleBarycentrics() : float2(0.0);
    if (hit.found) hit.objectToWorld = query.CommittedObjectToWorld3x4();
    return hit;
}
bool occluded(float3 origin, float3 direction, inout uint state) {
    state = hash(state + 0x9e3779b9u);
    RayDesc ray; ray.Origin = origin; ray.Direction = direction; ray.TMin = 0.0; ray.TMax = 100000.0;
    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
    query.TraceRayInline(scene, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, 0xff, ray);
    while (query.Proceed()) {
        if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
            Triangle tri = loadTriangle(query.CandidateInstanceID(), query.CandidatePrimitiveIndex());
            if (acceptsCoverage(tri, query.CandidateTriangleBarycentrics(),
                    origin + direction * query.CandidateTriangleRayT(), state)) query.CommitNonOpaqueTriangleHit();
        }
    }
    return query.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
}
float3 sky(float3 direction) {
    return lerp(float3(0.22, 0.27, 0.35), float3(0.36, 0.61, 1.0), saturate(direction.y * 0.5 + 0.5));
}
float3 cosineHemisphere(float3 normal, inout uint state) {
    float radius = sqrt(random(state)); float angle = 6.28318530718 * random(state);
    float3 tangent = normalize(cross(abs(normal.y) < 0.99 ? float3(0, 1, 0) : float3(1, 0, 0), normal));
    return normalize(tangent * (radius * cos(angle)) + cross(normal, tangent) * (radius * sin(angle)) + normal * sqrt(max(0.0, 1.0 - radius * radius)));
}
// Conservative camera-relative offset. This MVP does not yet implement the
// complete transform error bound from Prime's production precision contract.
float3 offsetOrigin(float3 position, float3 normal, float extent) {
    float error = max(0.00001, (max(max(abs(position.x), abs(position.y)), abs(position.z)) + extent) * 0.0000005);
    return position + normal * error;
}
[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint width = frame.dimensions.x, height = frame.dimensions.y;
    if (id.x >= width || id.y >= height) return;
    uint pixel = id.y * width + id.x;
    uint state = hash(pixel ^ hash(frame.target.y + 1));
    float2 jitter = float2(random(state), random(state));
    float2 screen = ((float2(id.xy) + jitter) / float2(width, height)) * 2.0 - 1.0;
    float3 origin = frame.position.xyz;
    float3 direction = normalize(frame.forward.xyz + frame.right.xyz * (screen.x * frame.forward.w * frame.position.w) - frame.up.xyz * (screen.y * frame.position.w));
    float3 radiance = float3(0.0), throughput = float3(1.0);
    float3 sunDirection = normalize(float3(-0.45, 0.83, 0.33));
    for (uint bounce = 0; bounce < 4; ++bounce) {
        Hit hit = trace(origin, direction, state);
        if (!hit.found) { radiance += throughput * sky(direction); break; }
        Triangle tri = loadTriangle(hit.instance, hit.primitive);
        float3 b = float3(1.0 - hit.bary.x - hit.bary.y, hit.bary.x, hit.bary.y);
        // Translation must not enter edge differences: small local geometry can
        // disappear when subtracting separately rounded large world positions.
        float3 localPosition = tri.p0.xyz * b.x + tri.p1.xyz * b.y + tri.p2.xyz * b.z;
        float3 position = mul(hit.objectToWorld, float4(localPosition, 1.0));
        float3 edge1 = mul(hit.objectToWorld, float4(tri.p1.xyz - tri.p0.xyz, 0.0));
        float3 edge2 = mul(hit.objectToWorld, float4(tri.p2.xyz - tri.p0.xyz, 0.0));
        float3 normal = normalize(cross(edge1, edge2));
        // All three edges make the scale estimate independent of triangle corner
        // order, including the shared diagonal of an affine-transformed quad.
        float extent = max(length(edge1), max(length(edge2), length(edge2 - edge1)));
        if (dot(normal, direction) > 0.0) normal = -normal;
        float3 albedo = linearColor(saturate(sourceColor(tri, hit.bary).rgb));
        origin = offsetOrigin(position, normal, extent);
        float cosine = saturate(dot(normal, sunDirection));
        if (cosine > 0.0 && !occluded(origin, sunDirection, state))
            radiance += throughput * albedo * float3(3.8, 3.5, 3.0) * (cosine / 3.14159265359);
        throughput *= albedo;
        direction = cosineHemisphere(normal, state);
        if (bounce >= 2) {
            float survival = clamp(max(max(throughput.r, throughput.g), throughput.b), 0.05, 0.95);
            if (random(state) >= survival) break;
            throughput /= survival;
        }
    }
    uint sample = frame.dimensions.z;
    float3 average = sample == 0 ? radiance : (accumulation[pixel].rgb * float(sample) + radiance) / float(sample + 1);
    accumulation[pixel] = float4(average, 1.0);
    uint3 encoded = uint3(saturate(displayColor(average)) * 255.0 + 0.5);
    uint2 targetPixel = uint2(id.x, frame.target.x != 0 ? frame.dimensions.y - 1 - id.y : id.y);
    outputImage[targetPixel] = float4(float3(encoded) / 255.0, 1.0);
}
