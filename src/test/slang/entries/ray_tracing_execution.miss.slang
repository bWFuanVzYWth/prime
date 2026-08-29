import glsl;
import "rt_execution_contract.slang";

layout(location = 0) rayPayloadInEXT PrimeRtExecutionPayload primeRtPayload;

[shader("miss")]
void main()
{
    primeRtPayload.flags = 1u;
    primeRtPayload.primitiveIndex = 0xffffffffu;
    primeRtPayload.instanceIndex = 0xffffffffu;
    primeRtPayload.barycentrics = float2(-1.0);
    primeRtPayload.distance = -1.0;
}
