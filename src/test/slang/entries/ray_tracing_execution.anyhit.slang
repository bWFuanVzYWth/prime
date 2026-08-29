import glsl;
import "rt_execution_contract.slang";

layout(location = 0) rayPayloadInEXT PrimeRtExecutionPayload primeRtPayload;

[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes)
{
    primeRtPayload.flags |= 2u;
}
