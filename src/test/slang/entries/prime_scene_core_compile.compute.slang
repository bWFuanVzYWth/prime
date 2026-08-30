import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "service/material/access.slang";
import "service/material/surface.slang";

[[vk::binding(0, 3)]] RWStructuredBuffer<uint4> primeSceneCoreProbe;

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    PrimitiveRecord primitive;
    primitive.uv0 = 0;
    primitive.uv1 = 0;
    primitive.uv2 = 0;
    primitive.tint = 0;
    primitive.mediumId = 0;
    primitive.flagsEmitter = 0;
    primitive.uvDensity = 0;
    primitive.tangent = 0;
    float2 uv = primeUnpackUv(primitive.uv0);
    primeSceneCoreProbe[dispatchThreadId.x] = uint4(
            primePrimitiveControl(primitive), asuint(uv), 0);
}
