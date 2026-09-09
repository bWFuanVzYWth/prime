// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.


import "model/atmosphere/epipolar.slang";



struct PrimeAlignmentCase {
    float4x4 inverseViewProjection;
    float4 epipolarDirection;
    float4 shadowDirection;
};

[[vk::binding(0, 0)]] StructuredBuffer<PrimeAlignmentCase> primeAlignmentInput;

layout(set = 0, binding = 1, std430) buffer PrimeAlignmentOutput {
    uint invalidCounts[];
} primeAlignmentOutput;

struct PrimeAlignmentPush {
    uint count;
};
[[vk::push_constant]] ConstantBuffer<PrimeAlignmentPush> primeAlignmentPush;

shared float3 primeAlignmentBasisU;
shared float3 primeAlignmentBasisV;
shared float2 primeAlignmentProfileDirection;

void primeAlignmentSunBasis(
        float3 directionToSun,
        out float3 basisU,
        out float3 basisV) {
    if (directionToSun.y < -0.9999999) {
        basisU = float3(1.0, 0.0, 0.0);
    } else {
        float reciprocal = 1.0 / (1.0 + directionToSun.y);
        basisU = normalize(float3(
                1.0
                        - directionToSun.x
                                * directionToSun.x
                                * reciprocal,
                -directionToSun.x,
                -directionToSun.x
                        * directionToSun.z
                        * reciprocal));
    }
    basisV = cross(directionToSun, basisU);
}

float3 primeAlignmentRayDirection(
        float4x4 inverseViewProjection,
        float2 epipoleNdc,
        int radial,
        int slice) {
    float2 ndc = atmEpipolarSliceNdc(
            epipoleNdc,
            int2(radial, slice),
            int2(128, 256));
    float4 nearPoint =
            inverseViewProjection * float4(ndc, 1.0, 1.0);
    float4 farPoint =
            inverseViewProjection * float4(ndc, 0.0, 1.0);
    return normalize(
            farPoint.xyz / farPoint.w
                    - nearPoint.xyz / nearPoint.w);
}

[numthreads(128, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID, uint3 groupThreadId : SV_GroupThreadID, uint3 groupId : SV_GroupID, uint groupIndex : SV_GroupIndex) {
    uint caseIndex = groupId.z;
    if (caseIndex >= primeAlignmentPush.count) {
        return;
    }
    PrimeAlignmentCase value = primeAlignmentInput[caseIndex];
    float3 shadowDirection = normalize(value.shadowDirection.xyz);
    float2 epipoleNdc = atmEpipolarProjectDirection(
            value.inverseViewProjection,
            normalize(value.epipolarDirection.xyz));
    int radial = int(groupThreadId.x);
    int slice = int(groupId.y);

    if (radial == 0) {
        primeAlignmentSunBasis(
                shadowDirection,
                primeAlignmentBasisU,
                primeAlignmentBasisV);
        float3 edgeDirection = primeAlignmentRayDirection(
                value.inverseViewProjection,
                epipoleNdc,
                127,
                slice);
        float2 projectedEdge = float2(
                dot(edgeDirection, primeAlignmentBasisU),
                dot(edgeDirection, primeAlignmentBasisV));
        primeAlignmentProfileDirection =
                normalize(projectedEdge);
    }
    GroupMemoryBarrierWithGroupSync();

    float3 rayDirection = primeAlignmentRayDirection(
            value.inverseViewProjection,
            epipoleNdc,
            radial,
            slice);
    float2 projectedRay = float2(
            dot(rayDirection, primeAlignmentBasisU),
            dot(rayDirection, primeAlignmentBasisV));
    float scalarRate =
            dot(projectedRay, primeAlignmentProfileDirection);
    float perpendicularRate = abs(
            projectedRay.x * primeAlignmentProfileDirection.y
                    - projectedRay.y * primeAlignmentProfileDirection.x);
    if (scalarRate < -1.0e-4
            || perpendicularRate > 2.0e-4) {
        atomicAdd(
                primeAlignmentOutput.invalidCounts[caseIndex],
                1u);
    }
}
