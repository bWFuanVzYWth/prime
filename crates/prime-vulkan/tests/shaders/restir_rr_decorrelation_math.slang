// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module restir_rr_decorrelation_math_test;
import "restir/rr_decorrelation.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputs;
[[vk::push_constant]] ConstantBuffer<uint4> dispatch;

[numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= dispatch.y)
        return;
    RestirRrDecorrelationConfig config;
    config.curve = asfloat(inputs[6u * i]);
    config.limits = asfloat(inputs[6u * i + 1u]);
    config.control = inputs[6u * i + 2u];
    uint4 values = inputs[6u * i + 3u];
    float smoothed = asfloat(values.x), reusedWeight = asfloat(values.y);
    uint age = values.z;
    bool temporal = (values.w & 1u) != 0u;
    bool firefly = (values.w & 2u) != 0u;
    bool previousValid = (values.w & 4u) != 0u;
    float4 initial[1] = {asfloat(inputs[6u * i + 4u])};
    float statistics[1] = {smoothed};
    uint flags[1] = {uint(firefly)};
    uint4 pixel = inputs[6u * i + 5u];
    float raw = restirRrStagnancy(age);
    outputs[4u * i] = asuint(
            float4(raw, restirRrSmoothedStagnancy(raw, smoothed, previousValid, config.curve.z),
                   restirRrProbability(config, temporal, smoothed, firefly),
                   restirRrFireflyMultiplier(config.curve.w)));
    outputs[4u * i + 1u] =
            asuint(float4(restirRrInitialEstimate(config, initial[0], reusedWeight, age),
                          restirRrOutputRandom(pixel.xy, pixel.z)));
    // The reused estimate is intentionally distinct from the saved initial estimate.
    float3 reused = float3(7, 11, 13);
    float4 *initialPointer = &initial[0];
    float *statisticsPointer = &statistics[0];
    uint *flagsPointer = &flags[0];
    if (!restirRrOutputActive(config, temporal)) {
        initialPointer = nullptr;
        statisticsPointer = nullptr;
        flagsPointer = nullptr;
    } else {
        if (config.control.y != RESTIR_RR_DECORRELATION_STAGNANCY)
            statisticsPointer = nullptr;
        if (config.control.w == 0u)
            flagsPointer = nullptr;
    }
    outputs[4u * i + 2u] =
            asuint(float4(restirRrDecorrelateEstimate(config, temporal, pixel.xy, pixel.z, 0u,
                                                      reused, reusedWeight, age, initialPointer,
                                                      statisticsPointer, flagsPointer),
                          float(restirRrOutputActive(config, temporal))));
    // Return the owned auxiliary data too: output selection must not mutate history.
    outputs[4u * i + 3u] = asuint(initial[0]);
}
