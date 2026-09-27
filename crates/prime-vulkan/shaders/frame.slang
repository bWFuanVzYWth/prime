#language slang 2026
module "frame.slang";

// 128-byte push constant contract shared by explicit renderer entry points.
public struct Frame {
    public float4 position;     // xyz camera-relative position; w tan(vertical FOV/2)
    public float4 forward;      // xyz unit forward; w aspect
    public float4 right;        // w sun intensity multiplier
    public float4 up;           // w sky intensity multiplier
    public uint4 dimensions;    // width, height, accumulated samples, samples this dispatch
    public uint4 target;        // host bottom-up, sequence, ceil(log2(max extent)), seed
    public float4 display;      // exposure, curve peak, encoded peak, hue compensation
    public float4 displayExtra; // saturation, depth display range, asuint(bounces), asuint(view)
};
