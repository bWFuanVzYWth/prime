// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "contract/trace/shadow_payload.slang";

public struct PrimeShadowPayload
{
    public float4 opticalDepthMomentHitDistance;
    public float4 terminalExtinctionRayDistance;
    public float4 startingExtinctionLastBoundary;
    public uint startingMediumId;
    public uint2 sourcePrimitive;
    public uint2 targetEmitter;
};
