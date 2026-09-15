// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/lambert.slang";

// Nearest-hit output. Before tracing, source identifies the sole semantic primitive to ignore;
// motion requests previous position only for the visible hit. All geometry remains f32.
public struct PrimeLambertPayload {
    public float3 position;
    public float distance;
    public float3 normal;
    public uint triangle;
    public float3 albedo;
    public uint section;
    public float3 previousPosition;
    public uint motion;
    // Lambert: local emission; delta: extinction on the adjacent side.
    public float3 auxiliary;
    public uint media; // two exact renderer uint16 MediumIds
    public float3 previousNormal;
    public uint flags;
};

public struct PrimeLambertShadowPayload {
    public uint2 source;
    public uint2 target;
    public float3 position;
    public float distance;
    public float3 extinction;
    public uint media;
    public float3 adjacentExtinction;
    public uint flags;
};

// One owner per pixel. Trace overwrites the current surface; shade publishes at most one successor.
// First direction and accumulated radiance survive every later trace/shade.
public struct PrimeLambertRecord {
    public float4 origin; // current position, extinction R
    public float4 direction; // ray direction, extinction G
    public float4 throughput; // physical beta, extinction B
    public float4 normalDistance;
    public float4 albedo; // current Lambert color, exact current MediumId
    public uint4 identity; // section, triangle, Lambert scatters, path flags
    public float4 radiance; // RGB sum, first secondary distance (0 unsampled; 65504 traced miss)
    public float4 firstDirection; // first interface normal until first shade; then cosine direction in guide space
};

// Only lives from camera through guide publication. No second lighting path is allocated.
public struct PrimeLambertInterface {
    public float4 positionFresnel;
    public float4 previousPositionTriangle;
    public float4 previousNormalSection;
};

public static const uint PRIME_LAMBERT_DELTA = 1u;
public static const uint PRIME_LAMBERT_THIN = 2u;
public static const uint PRIME_LAMBERT_WATER = 4u;
public static const uint PRIME_LAMBERT_ENTERING = 8u;
public static const uint PRIME_LAMBERT_EMITTER = 16u;
public static const uint PRIME_LAMBERT_VISIBLE = 1u;
public static const uint PRIME_LAMBERT_TRANSPARENT = 2u;
public static const uint PRIME_LAMBERT_REFLECTION = 4u;
public static const uint PRIME_LAMBERT_LIMIT = 8u;
public static const uint PRIME_LAMBERT_GUIDE_QUEUE = 2u;
