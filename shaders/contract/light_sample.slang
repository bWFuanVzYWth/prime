// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/light_sample.slang";

public struct LightSample {
    public float3 direction;
    public float distance;
    public float3 radiance;
    public float pdf;
    public uint isDelta;
};

// Exact result of the two-level light-tree walk. Independent cell and position samples are
// reconstructed by the Direct stage from the unchanged path sample identity.
public struct AreaLightSelection {
    public uint sectionIndex;
    public uint emitterIndex;
    public float pdf;
};

public struct AreaLightSample {
    public LightSample light;
    public uint sectionIndex;
    public uint emitterIndex;
    public float2 uv;
};

public struct LightEvaluation {
    public float3 radiance;
    public float pdf;
};
