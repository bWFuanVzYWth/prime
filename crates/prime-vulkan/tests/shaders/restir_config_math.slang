// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module restir_config_math_test;
import "restir/config.slang";
import "restir/reservoir.slang";
import "restir/rng.slang";
[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputs;
[[vk::push_constant]] ConstantBuffer<uint4> dispatch;
[numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint index = id.x;
    if (index >= dispatch.y)
        return;
    uint4 a = inputs[2u * index], b = inputs[2u * index + 1u];
    RestirConfig config = {};
    if (dispatch.x == 0u) {
        config.reuse = a;
        config.features.y = b.x;
        config.queue.w = b.y;
        config.validation.z = asfloat(b.z);
        outputs[2u * index] = uint4(restirStreamCount(config), restirTemporalStream(config),
                                    restirSpatialStream(config, dispatch.z),
                                    asuint(restirDuplicateMCap(config, b.w)));
        outputs[2u * index + 1u] = uint4(restirTemporalSurvival(config, false, dispatch.w),
                                         restirTemporalSurvival(config, true, dispatch.w), 0, 0);
    } else if (dispatch.x == 1u) {
        config.shift = asfloat(a);
        SampleGenerator sg = SampleGenerator(b.x);
        float distance, roughness;
        restirConfigThresholds(config, sg, distance, roughness);
        outputs[2u * index] = uint4(asuint(distance), asuint(roughness), sg.rng, 0);
    } else if (dispatch.x == 2u) {
        config.features.x = a.z;
        SampleGenerator sg = SampleGenerator(b.x);
        float2 location = restirStochasticLocation(asfloat(a.xy), config, sg);
        outputs[2u * index] = uint4(asuint(location), sg.rng, 0);
    } else if (dispatch.x == 4u) {
        outputs[2u * index] = config.reuse;
        outputs[2u * index + 1u] = asuint(config.shift);
    } else {
        SampleGenerator sg = SampleGenerator(uint2(index, 73u), dispatch.z);
        PathReservoir aggregate = {}, path = {};
        aggregate.init();
        path.M = 1.0;
        float3 sum = float3(0);
        for (uint i = 0u; i < a.w; ++i) {
            path.integrand = float3(float(i + 1u), float((i % 3u) + 1u), 0.25);
            path.weight = 0.5 + float(i % 2u);
            sum += path.integrand * path.weight;
            aggregate.merge(path.integrand, 1.0, path, sg, 1.0);
        }
        restirFinalizeFresh(aggregate, a.w);
        outputs[2u * index] = asuint(float4(aggregate.integrand * aggregate.weight, aggregate.M));
        outputs[2u * index + 1u] = asuint(float4(sum / float(a.w), 0));
    }
}
