#language slang 2026
module "realtime/post_parameters.slang";

// 112-byte push ABI. No scene geometry, material, medium or path state.
public struct PostParameters {
    public float4 display;
    public float4 extra;   // saturation, depth range, sun intensity, reserved
    public uint4 sampling; // input width, height, sequence, seed
    public uint4 output;   // output width, height, bottom-up, diagnostic view
    public float4 sample;  // jitter.xy, asfloat(Sobol R), asfloat(reconstruction enabled)
    public float4 *prefixAddress;
    public float4 *tailAddress;
    public float4 *diagnosticAddress;
    public uint2 reserved;
};
