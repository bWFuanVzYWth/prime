// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

layout(set = 0, binding = 0, std430) readonly buffer PrimeSrgbInput {
    uint unused[];
} primeSrgbInput;

layout(set = 0, binding = 1, std430) writeonly buffer PrimeSrgbOutput {
    float4 values[];
} primeSrgbOutput;

layout(set = 0, binding = 2) uniform sampler2D primeSrgbColor;
layout(set = 0, binding = 3) uniform sampler2D primeUnormData;

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    float2 blackWhiteMidpoint = float2(1.0 / 3.0, 0.5);
    primeSrgbOutput.values[0] = textureLod(
            primeSrgbColor, blackWhiteMidpoint, 0.0);
    primeSrgbOutput.values[1] = textureLod(
            primeUnormData, blackWhiteMidpoint, 0.0);
    primeSrgbOutput.values[2] = texelFetch(primeSrgbColor, int2(2, 0), 0);
    primeSrgbOutput.values[3] = texelFetch(primeUnormData, int2(2, 0), 0);
}
