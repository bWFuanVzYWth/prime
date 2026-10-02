#language slang 2026
module "realtime/transport_parameters.slang";

// 80-byte push ABI. No camera, guide, prefix/primary distance, history or display state.
public struct TransportParameters {
    public uint4 sampling;  // input width, height, sequence, seed
    public uint4 config;    // Sobol R, maximum vertices, reserved, reserved
    public float4 lighting; // sun intensity, sky intensity, cone spread, reserved
    public float4 *commonAddress;
    public float4 *opticalAddress;
    public float4 *tailAddress;
    public uint2 reserved;
};
