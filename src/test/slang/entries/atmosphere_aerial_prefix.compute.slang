// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.



import "prime_abi_types.slang";



[[vk::binding(0, 0)]] StructuredBuffer<float4> primeAerialPrefixInput;

[[vk::binding(1, 0)]] RWStructuredBuffer<float4> primeAerialPrefixOutput;

shared float4 prefixRadianceLow[PRIME_ATMOSPHERE_AERIAL_DEPTH];
shared float4 prefixTransmittanceLow[PRIME_ATMOSPHERE_AERIAL_DEPTH];
shared float4 prefixRadianceHigh[PRIME_ATMOSPHERE_AERIAL_DEPTH];
shared float4 prefixTransmittanceHigh[PRIME_ATMOSPHERE_AERIAL_DEPTH];

[numthreads(PRIME_ATMOSPHERE_AERIAL_DEPTH, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID, uint3 groupThreadId : SV_GroupThreadID, uint3 groupId : SV_GroupID, uint groupIndex : SV_GroupIndex) {
    uint depth = groupThreadId.x;
    uint base = depth * 4u;
    prefixRadianceLow[depth] = primeAerialPrefixInput[base];
    prefixTransmittanceLow[depth] = primeAerialPrefixInput[base + 1u];
    prefixRadianceHigh[depth] = primeAerialPrefixInput[base + 2u];
    prefixTransmittanceHigh[depth] = primeAerialPrefixInput[base + 3u];
    GroupMemoryBarrierWithGroupSync();

    for (uint offset = 1u;
            offset < PRIME_ATMOSPHERE_AERIAL_DEPTH;
            offset <<= 1u) {
        float4 radianceLow = prefixRadianceLow[depth];
        float4 transmittanceLow = prefixTransmittanceLow[depth];
        float4 radianceHigh = prefixRadianceHigh[depth];
        float4 transmittanceHigh = prefixTransmittanceHigh[depth];
        if (depth >= offset) {
            float4 earlierRadianceLow = prefixRadianceLow[depth - offset];
            float4 earlierTransmittanceLow = prefixTransmittanceLow[depth - offset];
            float4 earlierRadianceHigh = prefixRadianceHigh[depth - offset];
            float4 earlierTransmittanceHigh =
                    prefixTransmittanceHigh[depth - offset];
            radianceLow = earlierRadianceLow
                    + earlierTransmittanceLow * radianceLow;
            transmittanceLow *= earlierTransmittanceLow;
            radianceHigh = earlierRadianceHigh
                    + earlierTransmittanceHigh * radianceHigh;
            transmittanceHigh *= earlierTransmittanceHigh;
        }
        GroupMemoryBarrierWithGroupSync();
        prefixRadianceLow[depth] = radianceLow;
        prefixTransmittanceLow[depth] = transmittanceLow;
        prefixRadianceHigh[depth] = radianceHigh;
        prefixTransmittanceHigh[depth] = transmittanceHigh;
        GroupMemoryBarrierWithGroupSync();
    }

    primeAerialPrefixOutput[base] = prefixRadianceLow[depth];
    primeAerialPrefixOutput[base + 1u] = prefixTransmittanceLow[depth];
    primeAerialPrefixOutput[base + 2u] = prefixRadianceHigh[depth];
    primeAerialPrefixOutput[base + 3u] = prefixTransmittanceHigh[depth];
}
