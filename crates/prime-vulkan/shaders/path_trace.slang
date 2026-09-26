// GPU ABI is packed explicitly in vulkan/mod.rs; colors remain source-encoded
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
    uint4 target; // x: write bottom-up for the Minecraft main target
};
[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
[[vk::binding(1, 0)]] StructuredBuffer<Triangle> triangles;
[[vk::binding(2, 0)]] StructuredBuffer<uint4> textures; // offset, width, height, reserved
[[vk::binding(3, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;
[[vk::binding(5, 0)]] RWStructuredBuffer<float4> accumulation;
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
struct Hit { uint primitive; float2 bary; float3 instanceOrigin; bool found; };
Hit trace(float3 origin, float3 direction) {
    RayDesc ray; ray.Origin = origin; ray.Direction = direction; ray.TMin = 0.0; ray.TMax = 100000.0;
    RayQuery<RAY_FLAG_NONE> query;
    query.TraceRayInline(scene, RAY_FLAG_NONE, 0xff, ray);
    while (query.Proceed()) {
        if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
            Triangle candidate = triangles[query.CandidateInstanceID() + query.CandidatePrimitiveIndex()];
            if ((candidate.flags & 1) == 0 || sourceColor(candidate, query.CandidateTriangleBarycentrics()).a >= 0.1)
                query.CommitNonOpaqueTriangleHit();
        }
    }
    Hit hit; hit.found = query.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
    hit.primitive = hit.found ? query.CommittedInstanceID() + query.CommittedPrimitiveIndex() : 0;
    hit.bary = hit.found ? query.CommittedTriangleBarycentrics() : float2(0.0);
    hit.instanceOrigin = float3(0.0);
    if (hit.found) {
        float3x4 transform = query.CommittedObjectToWorld3x4();
        hit.instanceOrigin = float3(transform[0][3], transform[1][3], transform[2][3]);
    }
    return hit;
}
bool occluded(float3 origin, float3 direction) {
    RayDesc ray; ray.Origin = origin; ray.Direction = direction; ray.TMin = 0.0; ray.TMax = 100000.0;
    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
    query.TraceRayInline(scene, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, 0xff, ray);
    while (query.Proceed()) {
        if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
            Triangle tri = triangles[query.CandidateInstanceID() + query.CandidatePrimitiveIndex()];
            if (sourceColor(tri, query.CandidateTriangleBarycentrics()).a >= 0.1) query.CommitNonOpaqueTriangleHit();
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
float3 offsetOrigin(float3 position, float3 normal, Triangle tri) {
    float extent = max(length(tri.p1.xyz - tri.p0.xyz), length(tri.p2.xyz - tri.p0.xyz));
    float error = max(0.00001, (max(max(abs(position.x), abs(position.y)), abs(position.z)) + extent) * 0.0000005);
    return position + normal * error;
}
[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint width = frame.dimensions.x, height = frame.dimensions.y;
    if (id.x >= width || id.y >= height) return;
    uint pixel = id.y * width + id.x;
    uint state = hash(pixel ^ hash(frame.dimensions.z + 1));
    float2 jitter = float2(random(state), random(state));
    float2 screen = ((float2(id.xy) + jitter) / float2(width, height)) * 2.0 - 1.0;
    float3 origin = frame.position.xyz;
    float3 direction = normalize(frame.forward.xyz + frame.right.xyz * (screen.x * frame.forward.w * frame.position.w) - frame.up.xyz * (screen.y * frame.position.w));
    float3 radiance = float3(0.0), throughput = float3(1.0);
    float3 sunDirection = normalize(float3(-0.45, 0.83, 0.33));
    for (uint bounce = 0; bounce < 4; ++bounce) {
        Hit hit = trace(origin, direction);
        if (!hit.found) { radiance += throughput * sky(direction); break; }
        Triangle tri = triangles[hit.primitive];
        float3 b = float3(1.0 - hit.bary.x - hit.bary.y, hit.bary.x, hit.bary.y);
        float3 position = tri.p0.xyz * b.x + tri.p1.xyz * b.y + tri.p2.xyz * b.z + hit.instanceOrigin;
        float3 normal = normalize(cross(tri.p1.xyz - tri.p0.xyz, tri.p2.xyz - tri.p0.xyz));
        if (dot(normal, direction) > 0.0) normal = -normal;
        float3 albedo = linearColor(saturate(sourceColor(tri, hit.bary).rgb));
        origin = offsetOrigin(position, normal, tri);
        float cosine = saturate(dot(normal, sunDirection));
        if (cosine > 0.0 && !occluded(origin, sunDirection))
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
