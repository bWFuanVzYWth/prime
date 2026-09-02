import "prime_abi_types.slang";

[[vk::binding(0, 0)]] StructuredBuffer<PrimitiveRecord> primitiveRecords;
[[vk::binding(1, 0)]] StructuredBuffer<SectionRecord> sectionRecords;
[[vk::binding(2, 0)]] StructuredBuffer<LightNode> lightNodes;
[[vk::binding(3, 0)]] StructuredBuffer<LightLeaf> lightLeaves;
[[vk::binding(5, 0)]] StructuredBuffer<LightEmitter> lightEmitters;
[[vk::binding(6, 0)]] StructuredBuffer<LightCell> lightCells;
[[vk::binding(7, 0)]] StructuredBuffer<SectionLightHeader> sectionLightHeaders;
[[vk::binding(8, 0)]] StructuredBuffer<IntegratorRecord> integratorRecords;
[[vk::binding(9, 0)]] StructuredBuffer<PathState> pathStates;
[[vk::binding(10, 0)]] StructuredBuffer<TracePayload> tracePayloads;
[[vk::binding(11, 0)]] StructuredBuffer<SurfaceInteraction> surfaceInteractions;
[[vk::binding(12, 0)]] StructuredBuffer<NrdMotionPushConstants> nrdMotionRecords;
[[vk::binding(14, 0)]] RWStructuredBuffer<uint> outputValues;
[[vk::binding(15, 0)]] StructuredBuffer<SunShadowQueryConstants> sunShadowQueries;

[[vk::push_constant]] ConstantBuffer<PrimePushConstants> primePush;

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint index = dispatchThreadId.x;
    uint value = primitiveRecords[index].tangent;
    value ^= uint(sectionRecords[index].surfaceRelationAddress);
    // BDA pointer arithmetic uses Slang's natural layout rather than StructuredBuffer's std430
    // layout. Exercise it separately so the host record stride is verified in both domains.
    value ^= uint(((SectionRecord*)primePush.sectionTableAddress)[index]
            .surfaceRelationAddress);
    value ^= lightNodes[index].directionChildReserved.z;
    value ^= lightLeaves[index].index;
    value ^= asuint(lightLeaves[index].power);
    value ^= lightEmitters[index].metadata.w;
    value ^= asuint(lightCells[index].probabilityMass);
    value ^= sectionLightHeaders[index].emitterCount;
    value ^= asuint(integratorRecords[index].environmentRadiance.w);
    value ^= pathStates[index].sampleSeed;
    value ^= tracePayloads[index].adjacentInterfaceControl;
    value ^= surfaceInteractions[index].adjacentInterfaceControl;
    value ^= asuint(nrdMotionRecords[index].currentJitterPixels.y);
    value ^= sunShadowQueries[index].valid;
    value ^= asuint(primePush.inverseViewProjection[3][3]);
    value ^= primePush.path.w;
    outputValues[index] = value;
}
