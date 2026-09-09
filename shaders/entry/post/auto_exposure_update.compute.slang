// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/post/auto_exposure_update.compute.slang";

import "service/reconstruct/auto_exposure.slang";

struct PrimeAutoExposureUpdatePushConstants
{
    float deltaSeconds;
    uint reset;
    uint instant;
    float compensation;
};

[[vk::binding(3, 0)]] StructuredBuffer<uint> primeAutoExposureHistogram;
[[vk::binding(4, 0)]] RWStructuredBuffer<PrimeAutoExposureState> primeAutoExposureState;
[[vk::push_constant]]
ConstantBuffer<PrimeAutoExposureUpdatePushConstants> primeAutoExposureUpdatePush;

[numthreads(1, 1, 1)]
void main()
{
    uint validCount = primeAutoExposureHistogram[256];
    PrimeAutoExposureState state = primeAutoExposureState[0];
    if (validCount == 0) {
        if (primeAutoExposureUpdatePush.reset != 0
                || primeAutoExposureUpdatePush.instant != 0
                || state.initialized == 0) {
            state.exposureEv = 0.0;
            state.initialized = 1;
            state.targetEv = 0.0;
            state.measuredLogBrightness = 0.0;
            primeAutoExposureState[0] = state;
        }
        return;
    }

    uint tailCount = validCount / PRIME_AUTO_EXPOSURE_TAIL_DENOMINATOR;
    uint keepBegin = tailCount;
    uint keepEnd = validCount - tailCount;
    uint cursor = 0;
    uint retainedCount = 0;
    float weightedLogBrightness = 0.0;
    float minimumLogBrightness = 0.0;
    float maximumLogBrightness = 0.0;
    for (uint bin = 0; bin < 256; ++bin) {
        uint binCount = primeAutoExposureHistogram[bin];
        uint binBegin = cursor;
        uint binEnd = cursor + binCount;
        uint retainedBegin = max(binBegin, keepBegin);
        uint retainedEnd = min(binEnd, keepEnd);
        if (retainedEnd > retainedBegin) {
            uint retained = retainedEnd - retainedBegin;
            float binLogBrightness = primeAutoExposureBinLogBrightness(bin);
            if (retainedCount == 0) minimumLogBrightness = binLogBrightness;
            maximumLogBrightness = binLogBrightness;
            weightedLogBrightness += binLogBrightness * float(retained);
            retainedCount += retained;
        }
        cursor = binEnd;
    }
    if (retainedCount == 0) return;

    float measuredLogBrightness = weightedLogBrightness / float(retainedCount);
    float targetEv = primeAutoExposureTargetEv(
            measuredLogBrightness,
            minimumLogBrightness,
            maximumLogBrightness,
            primeAutoExposureUpdatePush.compensation);
    float exposureEv = targetEv;
    if (primeAutoExposureUpdatePush.reset == 0
            && primeAutoExposureUpdatePush.instant == 0
            && state.initialized != 0) {
        exposureEv = primeAutoExposureAdapt(
                state.exposureEv,
                targetEv,
                primeAutoExposureUpdatePush.deltaSeconds);
    }

    state.exposureEv = exposureEv;
    state.initialized = 1;
    state.targetEv = targetEv;
    state.measuredLogBrightness = measuredLogBrightness;
    primeAutoExposureState[0] = state;
}
