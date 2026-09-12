// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
layout(set = 0, binding = 0, std430) readonly buffer Inputs { uint4 pixels[]; } inputData;
layout(set = 0, binding = 1, std430) writeonly buffer Outputs { float4 colors[]; } outputData;
layout(set = 0, binding = 2) uniform sampler2D starmap;
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint4 p = inputData.pixels[id.x];
    float2 uv = (float2(p.xy) + 0.5) / float2(textureSize(starmap, int(p.z)));
    outputData.colors[id.x * 2] = texelFetch(starmap, int2(p.xy), int(p.z));
    outputData.colors[id.x * 2 + 1] = textureLod(starmap, uv, float(p.z) + 0.5);
}
