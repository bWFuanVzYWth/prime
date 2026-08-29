import glsl;
import "rt_execution_contract.slang";

layout(location = 0) rayPayloadInEXT PrimeRtExecutionPayload primeRtPayload;

[shader("closesthit")]
void main(in BuiltInTriangleIntersectionAttributes attributes)
{
    primeRtPayload.flags |= 4u;
    primeRtPayload.primitiveIndex = PrimitiveIndex();
    primeRtPayload.instanceIndex = InstanceID();
    primeRtPayload.barycentrics = attributes.barycentrics;
    primeRtPayload.distance = RayTCurrent();
}
