// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "restir/rr_decorrelation.slang";
import "restir/rng.slang";

// RA-016, docs/restir-adaptations.md: Prime final-output implementation of the
// Bernoulli/Stagnancy/EMA mechanism described in NVIDIA's public ReSTIR PT docs:
// https://github.com/NVIDIA-RTX/RTXDI/blob/a6efab966b7c3b272da0461578eb56ac61c7cbff/Doc/RestirPT.md
// Numeric oracle: RTXDI-Library f12037fa8e97ebc08e9e3edfd2de528ed1772a4b.
// This is Prime-authored storage/math glue, not a Falcor or RTXDI source port.
// Adaptive selection and fresh-weight limiting do not imply strict unbiasedness.
public static const uint RESTIR_RR_DECORRELATION_NONE = 0u;
public static const uint RESTIR_RR_DECORRELATION_UNIFORM = 1u;
public static const uint RESTIR_RR_DECORRELATION_STAGNANCY = 2u;
public static const uint RESTIR_RR_OUTPUT_ENABLED = 1u;
public static const uint RESTIR_RR_PREVIOUS_STATISTICS_VALID = 2u;

public struct RestirRrDecorrelationConfig {
    public float4 curve =
            float4(0.4, 0.5, 0.2, 0.7);           // probability, exponent, EMA, firefly strength
    public float4 limits = float4(15.0, 0, 0, 0); // fresh UCW multiplier, reserved
    public uint4 control =
            uint4(0, 2, 1, 1); // enabled/readiness flags, mode, limit weight, firefly
};

public bool restirRrOutputActive(RestirRrDecorrelationConfig config, bool temporalActive) {
    return temporalActive && (config.control.x & RESTIR_RR_OUTPUT_ENABLED) != 0u &&
           config.control.y != RESTIR_RR_DECORRELATION_NONE && config.curve.x > 0.0;
}

// Falcor's independently maintained age remains unchanged. Only this output
// statistic uses the reference's five-bit saturation and age/40 scale.
public float restirRrStagnancy(uint age) {
    return float(min(age, 31u)) / 40.0;
}

public float restirRrSmoothedStagnancy(float current, float previousAverage, bool previousValid,
                                       float ema) {
    if (!previousValid)
        return current;
    float a = saturate(ema);
    return a * current + (1.0 - a) * previousAverage;
}

public float restirRrProbability(RestirRrDecorrelationConfig config, bool temporalActive,
                                 float smoothedStagnancy, bool firefly) {
    if (!restirRrOutputActive(config, temporalActive))
        return 0.0;
    float probability = saturate(config.curve.x);
    if (config.control.y == RESTIR_RR_DECORRELATION_STAGNANCY) {
        // GLSL.std.450 Pow(0, 0) is undefined; exponent zero defines scale one.
        float exponent = max(0.0, config.curve.y);
        float scale = exponent == 0.0 ? 1.0 : pow(saturate(smoothedStagnancy), exponent);
        probability = saturate(4.0 * probability * scale);
    }
    if (config.control.w != 0u && firefly)
        probability = 1.0;
    return probability;
}

public float restirRrFireflyMultiplier(float strength) {
    float s = clamp(strength, 1e-6, 1.0);
    return 1.0 + 10.0 * (1.0 - s) / s;
}

// Initial RGB is already integrand*UCW in the same working space as resolve.
// Keeping its UCW preserves the fresh-weight limit without an 80B reservoir copy.
public float3 restirRrInitialEstimate(RestirRrDecorrelationConfig config, float4 initial,
                                      float reusedWeight, uint age) {
    float multiplier = 1.0;
    if (config.control.z != 0u && age == 0u && initial.w > 0.0)
        multiplier = min(1.0, max(0.0, config.limits.x) * max(0.0, reusedWeight) / initial.w);
    return initial.xyz * multiplier;
}

// Independent final-output stream; path, temporal and spatial generators are
// neither read nor advanced here. Its tag belongs only to this consumer.
public float restirRrOutputRandom(uint2 pixel, uint frameSample) {
    SampleGenerator outputRng = TinyUniformSampleGenerator(pixel, frameSample ^ 0xa511e9b3u);
    return sampleNext1D(outputRng);
}

// All auxiliary dereferences are inside their actual-consumer conditions. None,
// disabled and cold frames can pass inactive pointers without reading storage.
public float3 restirRrDecorrelateEstimate(RestirRrDecorrelationConfig config, bool temporalActive,
                                          uint2 pixel, uint frameSample, uint offset,
                                          float3 reusedEstimate, float reusedWeight, uint age,
                                          float4 *initialEstimates, float *smoothedStagnancy,
                                          uint *fireflies) {
    if (!restirRrOutputActive(config, temporalActive))
        return reusedEstimate;
    float smoothed = 0.0;
    if (config.control.y == RESTIR_RR_DECORRELATION_STAGNANCY)
        smoothed = smoothedStagnancy[offset];
    bool firefly = false;
    if (config.control.w != 0u)
        firefly = fireflies[offset] != 0u;
    float probability = restirRrProbability(config, temporalActive, smoothed, firefly);
    if (probability <= 0.0 || restirRrOutputRandom(pixel, frameSample) >= probability)
        return reusedEstimate;
    return restirRrInitialEstimate(config, initialEstimates[offset], reusedWeight, age);
}
