// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.


import "model/atmosphere/epipolar.slang";



struct PrimeEpipolarCase {
    float2 epipoleNdc;
    float2 screenNdc;
};

[[vk::binding(0, 0)]] StructuredBuffer<PrimeEpipolarCase> primeEpipolarInput;

[[vk::binding(1, 0)]] RWStructuredBuffer<float4> primeEpipolarOutput;

struct PrimeEpipolarPush {
    uint count;
};
[[vk::push_constant]] ConstantBuffer<PrimeEpipolarPush> primeEpipolarPush;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID, uint3 groupThreadId : SV_GroupThreadID, uint3 groupId : SV_GroupID, uint groupIndex : SV_GroupIndex) {
    uint index = dispatchThreadId.x;
    if (index >= primeEpipolarPush.count) {
        return;
    }
    PrimeEpipolarCase value = primeEpipolarInput[index];
    AtmEpipolarRange range = atmEpipolarVisibleRange(
            value.epipoleNdc);
    float mappedCoordinate = atmEpipolarRangeCoordinate(
            range,
            value.epipoleNdc,
            value.screenNdc);
    AtmEpipolarSegment segment;
    if (range.parallel) {
        float2 perpendicular = float2(-range.axis.y, range.axis.x);
        float offset = lerp(
                -range.extent,
                range.extent,
                mappedCoordinate);
        segment = atmEpipolarRaySegment(
                perpendicular * offset - range.axis * 4.0,
                range.axis);
    } else {
        float mappedAngle =
                range.start + mappedCoordinate * range.span;
        segment = atmEpipolarRaySegment(
                value.epipoleNdc,
                float2(cos(mappedAngle), sin(mappedAngle)));
    }
    float radial = atmEpipolarRadialCoordinate(
            value.screenNdc,
            segment);
    float2 reconstructed = lerp(segment.entry, segment.exit, radial);
    float boundaryError = min(
            abs(abs(segment.exit.x) - 1.0),
            abs(abs(segment.exit.y) - 1.0));
    primeEpipolarOutput[index] = float4(
            reconstructed,
            boundaryError,
            segment.valid ? 1.0 : 0.0);
}
