// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "contract/atmosphere/solve.slang";

public struct AtmSolve {
    public uint firstHeight;
    public uint heightCount;
    public uint iteration;
    public uint padding;
};
[[vk::push_constant]] public ConstantBuffer<AtmSolve> solve;
