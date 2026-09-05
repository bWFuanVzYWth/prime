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
};

public struct PrimeLambertShadowPayload {
    public uint2 source;
    public float distance;
};

// One owner per pixel. Trace overwrites the current surface; shade publishes at most one successor.
// The first-direction and accumulated-radiance lanes survive bounces for reconstruction only.
public struct PrimeLambertRecord {
    public float4 origin;
    public float4 direction;
    public float4 throughput;
    public float4 normalDistance;
    public float4 albedo;
    public uint4 identity; // section, triangle, completed scatters, primary-surface flag
    public float4 radiance; // RGB sum, first secondary distance
    public float4 firstDirection; // cosine-sampled direction, reserved
};
