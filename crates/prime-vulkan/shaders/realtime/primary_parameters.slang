#language slang 2026
module "realtime/primary_parameters.slang";

// 128-byte push ABI. Only K1 sees camera projection and primary/post output addresses.
public struct PrimaryParameters {
    public float4 position;
    public float4 forward;
    public float4 right;   // w sun intensity
    public float4 up;      // w sky intensity
    public uint4 sampling; // input width, height, sequence, seed
    public uint4 config;   // Sobol R, maximum vertices, diagnostic enabled, reserved
    public float4 *commonAddress;
    public float4 *opticalAddress;
    public float4 *prefixAddress;
    public float4 *diagnosticAddress; // zero unless raw depth/normal view is needed
};
