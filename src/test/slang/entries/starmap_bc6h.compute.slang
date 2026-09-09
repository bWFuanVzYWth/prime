// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

layout(set = 0, binding = 0, std430) readonly buffer StarmapInput {
    uint2 pixels[];
} inputData;
layout(set = 0, binding = 1, std430) writeonly buffer StarmapOutput {
    float4 colors[];
} outputData;
layout(set = 0, binding = 2) uniform sampler2D starmap;

[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    int2 p = int2(inputData.pixels[id.x]);
    int2 extent = textureSize(starmap, 0);
    outputData.colors[id.x * 2] = texelFetch(starmap, p, 0);
    outputData.colors[id.x * 2 + 1] = textureLod(
            starmap, (float2(p) + float2(1.0)) / float2(extent), 0.0);
}
