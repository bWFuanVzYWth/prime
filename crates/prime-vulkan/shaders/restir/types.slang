// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "restir/types.slang";
import "restir/reservoir.slang";

// Pure GPU payload types keep resampling math independent of ray-query resources.
public struct RestirPrimary {
    public HitInfo hit;
    public uint coverageSeed;
};
public struct RestirReplayData {
    public ReconnectionData data;
    public float4 medium;
};
