#language slang 2026
module "atmosphere/view_types.slang";
public struct AtmChart {
    public float4 bounds;
    public float4 centers;
    public float4 weights;
    public float4 widths;
};
public struct AtmFrame {
    public float4 sun;
    public float4 tangent;
    public float4 bitangent;
    public float4 geometry; // height, horizon cosine, solar elevation, horizontal sun length
    public float4 camera;   // relative position, eye radius
    public float4 forward;  // forward, tan(fov/2)
    public float4 right;    // right, aspect
    public float4 up;
    public float4 epipole;
    public AtmChart charts[3];
    public float4 range;
    public float4 rangeExtra;
    public float4 shadowU;
    public float4 shadowV;
};
