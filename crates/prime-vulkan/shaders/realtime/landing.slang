#language slang 2026
module "realtime/landing.slang";
import "math/ray_offset.slang";
import "pbr/vertex.slang";

public static const uint PRIME_LANDING_BOUNCE_MASK = 0xffu;
public static const uint PRIME_LANDING_ACTIVE = 1u << 8;
public static const uint PRIME_LANDING_PHYSICAL_THIN = 1u << 9;
// K2 owns an optional reflection-distance query originating at this landing.
public static const uint PRIME_LANDING_GUIDE_DISTANCE = 1u << 10;

// Values at the first non-delta vertex, AFTER segment absorption, cone advance and emission,
// BEFORE NEE and continuation. No primary-camera, guide, prefix, or previous-MIS state.
public struct PrimeLanding {
    public SurfacePoint surface;
    public PrimePbrVertex material;
    public float3 direction;
    public float coneWidth;
    public float4 medium;
    public float4 incident;
    public float4 transmitted;
    public float3 throughput;
    public float etaScale;
    public uint flags;
};

// Seven common uint4/float4 SoA planes and two optional optical planes. The allocations are
// addressed through BDA, never exposed as one descriptor exceeding maxStorageBufferRange.
public float4 primeRealtimeLoadPlane(float4 *address, uint count, uint pixel, uint plane) {
    return address[plane * count + pixel];
}
public void primeRealtimeStorePlane(float4 *address, uint count, uint pixel, uint plane,
                                    float4 value) {
    address[plane * count + pixel] = value;
}
public uint primeLandingFlags(float4 *common, uint count, uint pixel) {
    return asuint(primeRealtimeLoadPlane(common, count, pixel, 1).w);
}
public void primeStoreInactiveLanding(float4 *common, uint count, uint pixel) {
    primeRealtimeStorePlane(common, count, pixel, 1, float4(0.0));
}
public void primeStoreLanding(float4 *common, float4 *optical, uint count, uint pixel,
                              PrimeLanding landing) {
    primeRealtimeStorePlane(common, count, pixel, 0,
                            float4(landing.surface.position, landing.surface.offset));
    primeRealtimeStorePlane(
            common, count, pixel, 1,
            float4(landing.surface.normal, asfloat(landing.flags | PRIME_LANDING_ACTIVE)));
    primeRealtimeStorePlane(common, count, pixel, 2,
                            float4(landing.material.baseColor, landing.material.roughness));
    primeRealtimeStorePlane(common, count, pixel, 3,
                            float4(landing.material.normal, asfloat(landing.material.control)));
    primeRealtimeStorePlane(common, count, pixel, 4, float4(landing.direction, landing.coneWidth));
    primeRealtimeStorePlane(common, count, pixel, 5, landing.medium);
    primeRealtimeStorePlane(common, count, pixel, 6, float4(landing.throughput, landing.etaScale));
    if (primePbrVertexIsDielectric(landing.material)) {
        if ((landing.flags & PRIME_LANDING_PHYSICAL_THIN) == 0)
            primeRealtimeStorePlane(optical, count, pixel, 0, landing.incident);
        primeRealtimeStorePlane(optical, count, pixel, 1, landing.transmitted);
    }
}
public PrimeLanding primeLoadLanding(float4 *common, float4 *optical, uint count, uint pixel) {
    float4 position = primeRealtimeLoadPlane(common, count, pixel, 0);
    float4 geometric = primeRealtimeLoadPlane(common, count, pixel, 1);
    float4 base = primeRealtimeLoadPlane(common, count, pixel, 2);
    float4 shading = primeRealtimeLoadPlane(common, count, pixel, 3);
    float4 ray = primeRealtimeLoadPlane(common, count, pixel, 4);
    float4 medium = primeRealtimeLoadPlane(common, count, pixel, 5);
    float4 throughput = primeRealtimeLoadPlane(common, count, pixel, 6);
    PrimeLanding landing;
    landing.surface = {position.xyz, geometric.xyz, position.w};
    landing.material = {base.xyz, shading.xyz, base.w, asuint(shading.w)};
    landing.flags = asuint(geometric.w);
    landing.direction = ray.xyz;
    landing.coneWidth = ray.w;
    landing.medium = medium;
    landing.throughput = throughput.xyz;
    landing.etaScale = throughput.w;
    landing.incident = medium;
    landing.transmitted = float4(0.0);
    if (primePbrVertexIsDielectric(landing.material)) {
        if ((landing.flags & PRIME_LANDING_PHYSICAL_THIN) == 0)
            landing.incident = primeRealtimeLoadPlane(optical, count, pixel, 0);
        landing.transmitted = primeRealtimeLoadPlane(optical, count, pixel, 1);
    }
    return landing;
}
