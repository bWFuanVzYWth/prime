#include "core/prime_test_abi.slang"
import "prime_offline_abi.slang";
import "prime_abi_types.slang";
import "service/bsdf/contract/sample.slang";
import "math/color_space.slang";
import "math/light_tree.slang";
import "math/transport.slang";
import "math/packed_geometry.slang";
import "model/material/transparent.slang";
import "service/bsdf/support/medium_identity.slang";
import "state/realtime/medium.slang";
import "state/realtime/detached_guide_medium.slang";
import "state/offline/queue_layout.slang";
import "state/offline/path_storage.slang";
import "state/path.slang";
import "state/surface_storage.slang";

import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";
import "policy/transport/path.slang";

static const uint PRIME_TRANSPORT_INPUT_WORDS = 6;
static const uint PRIME_TRANSPORT_WITNESS_WORDS = 10;

struct PrimeTransportResult
{
    uint mask;
    float4 first;
    float4 second;
    float4 third;
};

void primeTransportClassify(inout uint mask, float value)
{
    mask |= primeTestClassify(value);
}

void primeTransportClassify(inout uint mask, float3 value)
{
    mask |= primeTestClassify(value);
}

void primeCheckProduct(
        inout PrimeTransportResult result, float3 first, float3 second, float denominator)
{
    float3 value = primeProductOver(first, second, denominator);
    float3 rescaled = primeProductOver(first, second * 2.0, denominator * 2.0);
    float3 identity = primeProductOver(first, float3(denominator), denominator);
    primeTransportClassify(result.mask, value);
    if (any(value < float3(0.0))) result.mask |= PRIME_TEST_FAILURE_RANGE;
    if (!primeTestClose(value, rescaled, 0.0, 2.0e-6)) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    if (!primeTestClose(first, identity, 0.0, 2.0e-6)) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = float4(value, denominator);
    result.second = float4(rescaled, 0.0);
    result.third = float4(identity, 0.0);
}

void primeCheckMis(
        inout PrimeTransportResult result, float firstPdf, float secondPdf, float scale)
{
    float first = primePowerHeuristicValue(firstPdf, secondPdf);
    float second = primePowerHeuristicValue(secondPdf, firstPdf);
    float scaled = primePowerHeuristicValue(firstPdf * scale, secondPdf * scale);
    float overPdf = primePowerHeuristicOverPdfValue(firstPdf, secondPdf);
    primeTransportClassify(result.mask, float3(first, second, scaled));
    primeTransportClassify(result.mask, overPdf);
    if (first < 0.0 || first > 1.0 || second < 0.0 || second > 1.0 || overPdf < 0.0) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    if (!primeTestClose(first + second, 1.0, 2.0e-6, 2.0e-6)
            || !primeTestClose(first, scaled, 2.0e-6, 2.0e-6)
            || !primeTestClose(first, overPdf * firstPdf, 2.0e-6, 2.0e-6)) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = float4(first, second, scaled, overPdf);
}

void primeCheckAreaHitMis(
        inout PrimeTransportResult result,
        bool previousUsedAreaNee,
        bool previousCannotUseMis,
        float bsdfPdf,
        float lightPdf)
{
    float value = primeAreaHitMisWeightValue(
            previousUsedAreaNee, previousCannotUseMis, bsdfPdf, lightPdf);
    float expected = previousUsedAreaNee && !previousCannotUseMis
            ? primePowerHeuristicValue(bsdfPdf, lightPdf)
            : 1.0;
    primeTransportClassify(result.mask, value);
    if (value < 0.0 || value > 1.0 || asuint(value) != asuint(expected)) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = float4(value, expected, bsdfPdf, lightPdf);
}

void primeCheckTransmittance(
        inout PrimeTransportResult result, float3 extinction, float distance)
{
    float3 full = primeSegmentTransmittance(extinction, distance);
    float3 halfDistance = primeSegmentTransmittance(extinction, distance * 0.5);
    float3 later = primeSegmentTransmittance(extinction, distance + 1.0);
    float3 identity = primeSegmentTransmittance(extinction, 0.0);
    primeTransportClassify(result.mask, full);
    primeTransportClassify(result.mask, halfDistance);
    if (any(full < float3(0.0)) || any(full > float3(1.0))) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    if (any(later > full)
            || !primeTestClose(identity, float3(1.0), 0.0, 0.0)
            || !primeTestClose(full, halfDistance * halfDistance, 2.0e-6, 2.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = float4(full, distance);
    result.second = float4(halfDistance, 0.0);
    result.third = float4(later, 0.0);
}

void primeCheckRoulette(
        inout PrimeTransportResult result,
        float3 throughput,
        float etaScale,
        float sampleValue)
{
    float survival = primeRussianRouletteSurvival(throughput, etaScale);
    float expectedSurvival = min(sqrt(max(
            max(throughput.r, max(throughput.g, throughput.b)) * etaScale,
            0.0)), 1.0);
    primeTransportClassify(result.mask, survival);
    primeTransportClassify(result.mask, etaScale);
    if (survival < 0.0 || survival > 1.0
            || !primeTestClose(survival, expectedSurvival, 0.0, 0.0)
            || primeRussianRouletteApplies(0, 1)
            || !primeRussianRouletteApplies(1, 1)
            || !primeRussianRouletteApplies(2, 1)
            || primeRealtimeRouletteScatterIndex(0u) != 1u
            || primeRealtimeRouletteScatterIndex(1u) != 2u
            || primeRealtimeCompletedBounceCount(0u) != 1u
            || primeRealtimeCompletedBounceCount(7u) != 8u) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    if (!primeTransparentRouletteBlocked(true, true, false)
            || primeTransparentRouletteBlocked(false, true, false)
            || primeTransparentRouletteBlocked(true, false, false)
            || primeTransparentRouletteBlocked(true, true, true)
            || !primeTransparentTransportOwnsGuide(false, false)
            || !primeTransparentTransportOwnsGuide(true, true)
            || primeTransparentTransportOwnsGuide(false, true)
            || !primeTransparentPrimaryReflectionOnly(true, false, true)
            || primeTransparentPrimaryReflectionOnly(false, false, true)
            || primeTransparentPrimaryReflectionOnly(true, true, true)
            || primeTransparentPrimaryReflectionOnly(true, false, false)) {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }
    if ((0u & PRIME_PATH_PREVIOUS_NO_SUN_NEE) != 0u
            || (PRIME_PATH_PREVIOUS_NO_SUN_NEE
                    & PRIME_PATH_PREVIOUS_NO_SUN_NEE) == 0u) {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }
    if (survival > 0.0) {
        float3 reweighted = primeRussianRouletteReweight(throughput, survival);
        primeTransportClassify(result.mask, reweighted);
        if (any(reweighted < float3(0.0))
                || !primeTestClose(throughput, reweighted * survival, 1.0e-7, 2.0e-6)) {
            result.mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    }
    bool survives = sampleValue < survival;
    if (survives != (sampleValue < primeRussianRouletteSurvival(throughput, etaScale))) {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }
    result.first = float4(throughput, survival);
    result.second = float4(etaScale, etaScale, sampleValue, expectedSurvival);
}

void primeCheckOfflineRoulette(
        inout PrimeTransportResult result,
        float3 throughput,
        float etaScale,
        float relativeEta)
{
    float transmittedScale = primeEtaScaleAfterScatter(etaScale, relativeEta, true);
    float reflectedScale = primeEtaScaleAfterScatter(etaScale, relativeEta, false);
    float restoredScale = primeEtaScaleAfterScatter(
            transmittedScale, 1.0 / relativeEta, true);
    float metric = primeRussianRouletteMetric(throughput, etaScale);
    float survival = primeRussianRouletteSurvival(throughput, etaScale);
    float expectedMetric = max(throughput.r, max(throughput.g, throughput.b)) * etaScale;
    float expectedSurvival = min(sqrt(max(expectedMetric, 0.0)), 1.0);
    primeTransportClassify(result.mask, float3(transmittedScale, restoredScale, survival));
    if (survival < 0.0 || survival > 1.0
            || transmittedScale < 0.0 || restoredScale < 0.0
            || !primeTestClose(reflectedScale, etaScale, 0.0, 0.0)
            || !primeTestClose(restoredScale, etaScale, 2.0e-6, 2.0e-6)
            || !primeTestClose(metric, expectedMetric, 0.0, 0.0)
            || !primeTestClose(survival, expectedSurvival, 0.0, 0.0)) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    if (survival > 0.0) {
        float3 reweighted = primeRussianRouletteReweight(throughput, survival);
        primeTransportClassify(result.mask, reweighted);
        if (!primeTestClose(throughput, reweighted * survival, 1.0e-7, 2.0e-6)) {
            result.mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    }
    result.first = float4(transmittedScale, restoredScale, metric, survival);
    result.second = float4(throughput, etaScale);
    result.third = float4(relativeEta, expectedMetric, expectedSurvival, 0.0);
}

void primeCheckAreaPdf(
        inout PrimeTransportResult result,
        float areaPdf,
        float distanceSquared,
        float cosine)
{
    float value = primeAreaSolidAnglePdfValue(areaPdf, distanceSquared, cosine);
    float rescaled = primeAreaSolidAnglePdfValue(
            areaPdf * 0.5, distanceSquared * 2.0, cosine);
    primeTransportClassify(result.mask, value);
    if (value < 0.0) result.mask |= PRIME_TEST_FAILURE_RANGE;
    if (!primeTestClose(value, rescaled, 0.0, 2.0e-6)) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = float4(value, rescaled, areaPdf, cosine);
}

void primeCheckGlassVolume(
        inout PrimeTransportResult result, float3 baseColor, float opacity)
{
    float3 transmittance = primeGlassVolumeTransmittance(baseColor, opacity);
    float3 lowCoverage = primeGlassVolumeTransmittance(baseColor, 0.0);
    float3 fullCoverage = primeGlassVolumeTransmittance(baseColor, 1.0);
    float3 linearSrgb = clamp(
            primeLinearRec2020ToLinearBt709(baseColor), float3(0.0), float3(1.0));
    float3 encodedSrgb = primeEncodeSrgb(linearSrgb);
    float coverage = clamp(opacity, 0.0, 1.0);
    float3 expected = clamp(
            primeLinearSrgbToLinearRec2020(
                    primeDecodeSrgb(lerp(float3(1.0), encodedSrgb, coverage))),
            float3(0.0),
            float3(1.0));
    float3 fullExpected = clamp(
            primeLinearSrgbToLinearRec2020(linearSrgb), float3(0.0), float3(1.0));
    float3 extinction = primeGlassVolumeExtinction(baseColor, opacity);
    float3 shadowExtinction = primeGlassShadowExtinction(baseColor, opacity);
    bool stained = primeGlassReferenceIsStained(opacity);
    bool rough = primeGlassTexelIsRough(opacity, false);
    bool seamlessRough = primeGlassTexelIsRough(opacity, true);
    float3 expectedShadowExtinction = stained
            ? primeGlassVolumeExtinction(
                    baseColor, PRIME_STAINED_GLASS_ABSORPTION_OPACITY)
            : float3(0.0);
    float3 oneMetre = exp(-extinction);
    float3 twoMetres = exp(-extinction * 2.0);
    float3 encodedRoundTrip = primeEncodeSrgb(clamp(
            primeLinearRec2020ToLinearBt709(transmittance),
            float3(0.0),
            float3(1.0)));
    primeTransportClassify(result.mask, transmittance);
    primeTransportClassify(result.mask, extinction);
    primeTransportClassify(result.mask, shadowExtinction);
    primeTransportClassify(result.mask, twoMetres);
    if (any(transmittance < float3(0.0))
            || any(transmittance > float3(1.0))
            || any(extinction < float3(0.0))
            || !primeTestClose(lowCoverage, float3(1.0), 2.0e-6, 2.0e-6)
            || !primeTestClose(fullCoverage, fullExpected, 2.0e-6, 2.0e-6)
            || !primeTestClose(transmittance, expected, 2.0e-6, 2.0e-6)
            || !primeTestClose(
                    extinction,
                    -log(max(float3(1.0e-3), transmittance)),
                    2.0e-6,
                    2.0e-6)
            || !primeTestClose(shadowExtinction, expectedShadowExtinction, 0.0, 0.0)
            || stained != (opacity >= PRIME_GLASS_STAINED_REFERENCE_THRESHOLD)
            || rough != (opacity > PRIME_GLASS_ROUGH_OPACITY_THRESHOLD)
            || seamlessRough
            || !primeTestClose(oneMetre, transmittance, 2.0e-6, 2.0e-6)
            || !primeTestClose(
                    twoMetres, transmittance * transmittance, 2.0e-6, 2.0e-6)
            || !primeTestClose(
                    encodedRoundTrip,
                    lerp(float3(1.0), encodedSrgb, coverage),
                    3.0e-6,
                    3.0e-6)) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = float4(transmittance, opacity);
    result.second = float4(extinction, 0.0);
    result.third = float4(twoMetres, 0.0);
}

void primeCheckShadowOpticalDepth(
        inout PrimeTransportResult result, float4 first, float4 second)
{
    float rayDistance = max(abs(first.x), 1.0e-6);
    float firstHit = clamp(first.y, 0.0, rayDistance);
    float secondHit = clamp(first.z, firstHit, rayDistance);
    float thirdHit = clamp(first.w, secondHit, rayDistance);
    float fourthHit = clamp(second.x, thirdHit, rayDistance);
    float3 firstExtinction = primeShadowCanonicalExtinction(
            PRIME_PURE_WATER_ABSORPTION_M_INV);
    float3 secondExtinction = primeShadowCanonicalExtinction(max(second.yzw, float3(0.0)));

    float3 forwardMoment = primeShadowBoundaryOpticalDepthMoment(
            float3(0.0), firstExtinction, firstHit, true);
    float3 forwardTerminal = primeShadowBoundaryTerminalExtinction(
            float3(0.0), firstExtinction, true);
    forwardMoment = primeShadowBoundaryOpticalDepthMoment(
            forwardMoment, secondExtinction, secondHit, true);
    forwardTerminal = primeShadowBoundaryTerminalExtinction(
            forwardTerminal, secondExtinction, true);
    forwardMoment = primeShadowBoundaryOpticalDepthMoment(
            forwardMoment, secondExtinction, thirdHit, false);
    forwardTerminal = primeShadowBoundaryTerminalExtinction(
            forwardTerminal, secondExtinction, false);
    forwardMoment = primeShadowBoundaryOpticalDepthMoment(
            forwardMoment, firstExtinction, fourthHit, false);
    forwardTerminal = primeShadowBoundaryTerminalExtinction(
            forwardTerminal, firstExtinction, false);

    float3 reverseMoment = primeShadowBoundaryOpticalDepthMoment(
            float3(0.0), firstExtinction, fourthHit, false);
    float3 reverseTerminal = primeShadowBoundaryTerminalExtinction(
            float3(0.0), firstExtinction, false);
    reverseMoment = primeShadowBoundaryOpticalDepthMoment(
            reverseMoment, secondExtinction, thirdHit, false);
    reverseTerminal = primeShadowBoundaryTerminalExtinction(
            reverseTerminal, secondExtinction, false);
    reverseMoment = primeShadowBoundaryOpticalDepthMoment(
            reverseMoment, secondExtinction, secondHit, true);
    reverseTerminal = primeShadowBoundaryTerminalExtinction(
            reverseTerminal, secondExtinction, true);
    reverseMoment = primeShadowBoundaryOpticalDepthMoment(
            reverseMoment, firstExtinction, firstHit, true);
    reverseTerminal = primeShadowBoundaryTerminalExtinction(
            reverseTerminal, firstExtinction, true);

    PrimeOpenPbrVolume queuedFirstMedium;
    queuedFirstMedium.extinction = PRIME_PURE_WATER_ABSORPTION_M_INV;
    queuedFirstMedium.ior = 1.333;
    queuedFirstMedium.albedo = float3(0.0);
    queuedFirstMedium.anisotropy = 0.0;
    queuedFirstMedium.mediumId = PRIME_MEDIUM_ID_WATER;
    queuedFirstMedium.iorEncoding = PRIME_MEDIUM_IOR_WATER;
    float3 queuedFirstExtinction = primeShadowCanonicalExtinction(
            primeUnpackWavefrontMedium(
                    primePackWavefrontMedium(queuedFirstMedium),
                    queuedFirstMedium.iorEncoding).extinction);
    float3 insideMoment = primeShadowBoundaryOpticalDepthMoment(
            float3(0.0), queuedFirstExtinction, firstHit, false);
    float3 insideTerminal = float3(0.0);
    insideMoment = primeShadowBoundaryOpticalDepthMoment(
            insideMoment, secondExtinction, secondHit, true);
    insideTerminal = primeShadowBoundaryTerminalExtinction(
            insideTerminal, secondExtinction, true);
    insideMoment = primeShadowBoundaryOpticalDepthMoment(
            insideMoment, secondExtinction, thirdHit, false);
    insideTerminal = primeShadowBoundaryTerminalExtinction(
            insideTerminal, secondExtinction, false);

    float thinCosine = max((secondHit - firstHit) / rayDistance, 1.0e-3);
    float3 thin = primeShadowThinOpticalDepth(
            float3(0.0), secondExtinction, rayDistance, thinCosine);
    float3 forward = primeShadowOpticalDepth(
            forwardMoment, forwardTerminal, float4(0.0), float4(0.0), 0, rayDistance);
    float3 reverse = primeShadowOpticalDepth(
            reverseMoment, reverseTerminal, float4(0.0), float4(0.0), 0, rayDistance);
    float3 inside = primeShadowOpticalDepth(
            insideMoment,
            insideTerminal,
            float4(queuedFirstExtinction, 0.0),
            float4(0.0),
            1,
            rayDistance);
    float3 unboundedStartingMedium = primeShadowOpticalDepth(
            float3(0.0),
            float3(0.0),
            float4(queuedFirstExtinction, 1.0),
            float4(0.0),
            1,
            rayDistance);
    float3 innerCoefficient = primeShadowStartingMediumCoefficient(
            firstExtinction, secondExtinction, 1);
    float3 nestedMoment = primeShadowBoundaryOpticalDepthMoment(
            float3(0.0), innerCoefficient, firstHit, false);
    nestedMoment = primeShadowBoundaryOpticalDepthMoment(
            nestedMoment, firstExtinction, fourthHit, false);
    float3 nested = primeShadowOpticalDepth(
            nestedMoment,
            float3(0.0),
            float4(firstExtinction, 0.0),
            float4(secondExtinction, 0.0),
            2,
            rayDistance);
    float3 unboundedNested = primeShadowOpticalDepth(
            float3(0.0),
            float3(0.0),
            float4(firstExtinction, 1.0),
            float4(secondExtinction, 1.0),
            2,
            rayDistance);

    float3 transmittance = exp(-forward);
    float3 expectedOutside = firstExtinction * (fourthHit - firstHit)
            + secondExtinction * (thirdHit - secondHit);
    float3 expectedInside = firstExtinction * firstHit
            + secondExtinction * (thirdHit - secondHit);
    float3 expectedNested = secondExtinction * firstHit
            + firstExtinction * (fourthHit - firstHit);
    float3 expectedThin = secondExtinction * min(
            PRIME_THIN_WALLED_THICKNESS_M / thinCosine, rayDistance);
    float distanceTolerance = 2.0e-6 + 2.0e-6 * fourthHit;
    primeTransportClassify(result.mask, forward);
    primeTransportClassify(result.mask, reverse);
    primeTransportClassify(result.mask, inside);
    primeTransportClassify(result.mask, nested);
    primeTransportClassify(result.mask, thin);
    primeTransportClassify(result.mask, transmittance);
    if (!primeTestClose(forward, expectedOutside, distanceTolerance, 2.0e-6)
            || !primeTestClose(forward, reverse, distanceTolerance, 2.0e-6)
            || any(queuedFirstExtinction != firstExtinction)
            || primeShadowStartingMediumIndex(
                    PRIME_MEDIUM_ID_WATER,
                    uint2(PRIME_MEDIUM_ID_WATER, 29u),
                    float4(firstExtinction, 1.0),
                    float4(secondExtinction, 1.0),
                    2u,
                    true) != 0
            || primeShadowStartingMediumIndex(
                    17u,
                    uint2(PRIME_MEDIUM_ID_WATER, 29u),
                    float4(firstExtinction, 1.0),
                    float4(secondExtinction, 1.0),
                    2u,
                    true) != -1
            || !primeTestClose(inside, expectedInside, distanceTolerance, 2.0e-6)
            || !primeTestClose(nested, expectedNested, distanceTolerance, 2.0e-6)
            || !primeTestClose(
                    unboundedNested,
                    secondExtinction * rayDistance,
                    distanceTolerance,
                    2.0e-6)
            || !primeTestClose(
                    unboundedStartingMedium,
                    firstExtinction * rayDistance,
                    distanceTolerance,
                    2.0e-6)
            || !primeTestClose(thin, expectedThin, distanceTolerance, 2.0e-6)
            || any(primeShadowOpticalDepth(
                    float3(-rayDistance),
                    float3(0.0),
                    float4(0.0),
                    float4(0.0),
                    0,
                    rayDistance) != float3(0.0))
            || any(transmittance < float3(0.0))
            || any(transmittance > float3(1.0))) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = float4(forward, rayDistance);
    result.second = float4(transmittance, 0.0);
    result.third = float4(nested, distanceTolerance);
}

void primeCheckWavefrontMedium(
        inout PrimeTransportResult result,
        uint2 identity,
        float4 transport,
        float4 coldVolumeFields)
{
    PrimeOpenPbrVolume medium;
    medium.extinction = transport.xyz;
    medium.ior = primeDecodeMediumIor(identity.y);
    medium.albedo = coldVolumeFields.xyz;
    medium.anisotropy = coldVolumeFields.w;
    medium.mediumId = identity.x;
    medium.iorEncoding = identity.y;
    uint4 packed = primePackWavefrontMedium(medium);
    PrimeOpenPbrVolume restored = primeUnpackWavefrontMedium(packed, identity.y);
    PrimeOpenPbrVolumeStack stack;
    stack.values[0] = medium;
    stack.values[1] = medium;
    stack.count = 2u;
    uint packedIors = primePackWavefrontMediumIors(stack);
    uint guideControl = 0x00007f07u;
    uint mergedControl = primeMergeWavefrontGuideControl(packedIors, guideControl);
    primeTransportClassify(result.mask, restored.extinction);
    primeTransportClassify(result.mask, restored.ior);
    if (any(primePackWavefrontMedium(restored) != packed)
            || any(restored.albedo != float3(0.0))
            || restored.anisotropy != 0.0
            || any(asuint(restored.extinction) != asuint(medium.extinction))
            || asuint(restored.ior) != asuint(medium.ior)
            || restored.mediumId != medium.mediumId
            || restored.iorEncoding != medium.iorEncoding
            || primeUnpackWavefrontMediumIor(packedIors, 0u) != identity.y
            || primeUnpackWavefrontMediumIor(packedIors, 1u) != identity.y
            || (mergedControl & ~PRIME_WAVEFRONT_GUIDE_CONTROL_MASK)
                    != (packedIors & ~PRIME_WAVEFRONT_GUIDE_CONTROL_MASK)
            || (mergedControl & PRIME_WAVEFRONT_GUIDE_CONTROL_MASK)
                    != (guideControl & PRIME_WAVEFRONT_GUIDE_CONTROL_MASK)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    result.first = float4(restored.extinction, restored.ior);
    result.second = transport;
    result.third = float4(restored.albedo, restored.anisotropy);
}

void primeCheckDetachedGuideMedium(
        inout PrimeTransportResult result,
        uint count,
        uint2 mediumIds,
        uint2 iorEncodings)
{
    PrimeOpenPbrVolumeStack stack;
    stack.count = count;
    [unroll]
    for (uint index = 0u; index < 2u; ++index) {
        stack.values[index].mediumId = mediumIds[index];
        stack.values[index].iorEncoding = iorEncodings[index];
        stack.values[index].ior = primeDecodeMediumIor(iorEncodings[index]);
    }
    uint2 packed = primePackDetachedGuideMediumStack(stack);
    PrimeOpenPbrVolumeStack restored =
            primeUnpackDetachedGuideMediumStack(packed);
    bool exact = restored.count == count;
    [unroll]
    for (uint index = 0u; index < 2u; ++index) {
        bool active = index < count;
        exact = exact
                && restored.values[index].mediumId
                        == (active ? mediumIds[index] : PRIME_MEDIUM_ID_VACUUM)
                && restored.values[index].iorEncoding
                        == (active ? iorEncodings[index] : 0u)
                && asuint(restored.values[index].ior) == asuint(active
                        ? primeDecodeMediumIor(iorEncodings[index])
                        : 1.0)
                && all(restored.values[index].extinction == float3(0.0))
                && all(restored.values[index].albedo == float3(0.0))
                && restored.values[index].anisotropy == 0.0;
    }
    if (!exact) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = asfloat(uint4(packed, mediumIds));
    result.second = asfloat(uint4(iorEncodings, count, restored.count));
}

void primeCheckPreviousDynamicSurface(
        inout PrimeTransportResult result,
        float4 firstTranslationX,
        float4 secondTranslationY,
        float4 thirdTranslationZ,
        float2 barycentrics)
{
    float2 restoredBarycentrics = barycentrics;
    float3 translation = float3(
            firstTranslationX.w,
            secondTranslationY.w,
            thirdTranslationZ.w);
    PrimePreviousDynamicSurface previous = primePreviousDynamicSurfaceValue(
            firstTranslationX.xyz,
            secondTranslationY.xyz,
            thirdTranslationZ.xyz,
            translation,
            restoredBarycentrics);
    float3 firstEdge = secondTranslationY.xyz - firstTranslationX.xyz;
    float3 secondEdge = thirdTranslationZ.xyz - firstTranslationX.xyz;
    float3 crossValue = cross(firstEdge, secondEdge);
    float crossLengthSquared = dot(crossValue, crossValue);
    float3 expectedNormal = crossLengthSquared > 1.0e-20
            ? crossValue * rsqrt(crossLengthSquared)
            : float3(0.0, 1.0, 0.0);
    float3 expectedPosition = firstTranslationX.xyz
            + restoredBarycentrics.x * firstEdge
            + restoredBarycentrics.y * secondEdge
            + translation;
    primeTransportClassify(result.mask, previous.position);
    primeTransportClassify(result.mask, previous.geometricNormal);
    if (!primeTestClose(previous.position, expectedPosition, 1.0e-3, 5.0e-6)
            || !primeTestClose(previous.geometricNormal, expectedNormal, 2.0e-6, 2.0e-6)
            || !primeTestClose(
                    dot(previous.geometricNormal, previous.geometricNormal),
                    1.0,
                    2.0e-6,
                    2.0e-6)
            || any(asuint(restoredBarycentrics) != asuint(barycentrics))) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    result.first = float4(previous.position, restoredBarycentrics.x);
    result.second = float4(previous.geometricNormal, restoredBarycentrics.y);
    result.third = float4(expectedPosition, crossLengthSquared);
}

void primeCheckGlobalTriangleIndex(
        inout PrimeTransportResult result,
        uint4 control,
        uint4 counts,
        uint2 inverseExpected)
{
    uint index = primeGlobalTriangleIndexValue(
            control.y,
            control.z,
            counts.x,
            counts.y,
            counts.z,
            counts.w);
    uint primitive = primeGlobalPrimitiveIndexFromTriangleValue(
            index,
            counts.x,
            counts.y,
            counts.z,
            counts.w,
            inverseExpected.x);
    if (index != control.w || primitive != inverseExpected.y) {
        result.mask |= PRIME_TEST_FAILURE_INDEX;
    }
    result.first = float4(index, control.w, primitive, inverseExpected.y);
    result.second = asfloat(counts);
}

void primeCheckLightBranch(
        inout PrimeTransportResult result,
        float4 firstBoundsMinPower,
        float4 firstBoundsMaxSoftening,
        float4 secondBoundsMinPower,
        float4 secondBoundsMaxSoftening,
        float3 point,
        float3 receiverNormal)
{
    float2 firstMetrics = primeLightNodeMetrics(
            0.5 * (firstBoundsMinPower.xyz + firstBoundsMaxSoftening.xyz),
            firstBoundsMinPower.w,
            point,
            receiverNormal,
            0xc0000000);
    float2 secondMetrics = primeLightNodeMetrics(
            0.5 * (secondBoundsMinPower.xyz + secondBoundsMaxSoftening.xyz),
            secondBoundsMinPower.w,
            point,
            receiverNormal,
            0xc0000000);
    float probability = primeLightBranchProbability(firstMetrics, secondMetrics);
    float reverse = probability >= 0.0 ? 1.0 - probability : -1.0;
    primeTransportClassify(result.mask, float3(probability, reverse, 0.0));
    if (probability < -1.0
            || probability > 1.0 + 2.0e-6
            || any(firstMetrics < float2(0.0))
            || any(secondMetrics < float2(0.0))
            || (all(receiverNormal == float3(1.0, 0.0, 0.0)) && probability < 0.99)
            || (probability >= 0.0 && reverse >= 0.0
                    && !primeTestClose(probability + reverse, 1.0, 2.0e-7, 2.0e-6))) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = float4(firstMetrics, secondMetrics);
    result.second = float4(probability, reverse, 0.0, 0.0);
    result.third = float4(receiverNormal, 0.0);
}

void primeCheckLightReceiverNormal(
        inout PrimeTransportResult result, float3 receiverNormal)
{
    float3 stored = receiverNormal;
    float3 disabled = float3(0.0);
    primeTransportClassify(result.mask, stored);
    if (any(asuint(stored) != asuint(receiverNormal))
            || any(disabled != float3(0.0))) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    result.first = float4(stored, 0.0);
    result.second = float4(receiverNormal, 0.0);
    result.third = float4(disabled, 0.0);
}

void primeCheckLightEmissionBound(
        inout PrimeTransportResult result,
        uint packedDirection,
        float3 boundsMin,
        float3 boundsMax,
        float3 point,
        float3 lightPoint,
        float3 normal,
        bool twoSided)
{
    float3 closest = clamp(point, boundsMin, boundsMax);
    float3 closestDelta = point - closest;
    float bound = primeLightEmitterCosineBound(
            boundsMin,
            boundsMax,
            point,
            dot(closestDelta, closestDelta),
            packedDirection);
    float3 delta = point - lightPoint;
    float actual = dot(delta, delta) > 1.0e-12 ? dot(normal, normalize(delta)) : 1.0;
    actual = twoSided ? 0.5 * abs(actual) : max(actual, 0.0);
    primeTransportClassify(result.mask, float3(bound, actual, 0.0));
    if (bound < 0.0 || bound > 1.0 || bound + 2.0e-5 < actual) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    result.first = float4(bound, actual, asfloat(packedDirection), 0.0);
    result.second = float4(boundsMin, 0.0);
    result.third = float4(boundsMax, 0.0);
}

void primeCheckPointLightNodeMetric(
        inout PrimeTransportResult result,
        float4 lightPositionPower,
        float4 pointData,
        float3 receiverNormal)
{
    float2 metrics = primeLightNodeMetrics(
            lightPositionPower.xyz,
            lightPositionPower.w,
            pointData.xyz,
            receiverNormal,
            0xc0000000);
    float3 delta = lightPositionPower.xyz - pointData.xyz;
    float distanceSquared = dot(delta, delta);
    float3 direction = delta * rsqrt(distanceSquared);
    float receiverCosine = dot(receiverNormal, receiverNormal) > 0.0
            ? max(dot(receiverNormal, direction), 0.0)
            : 1.0;
    float expectedImportance = PRIME_LIGHT_RECEIVER_COSINE_FLOOR
            + (1.0 - PRIME_LIGHT_RECEIVER_COSINE_FLOOR) * receiverCosine;
    primeTransportClassify(result.mask, float3(metrics, 0.0));
    if (!primeTestClose(
                    metrics.x,
                    distanceSquared,
                    2.0e-6,
                    2.0e-6)
            || !primeTestClose(
                    metrics.y,
                    lightPositionPower.w * expectedImportance,
                    6.0e-7,
                    8.0e-6)) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = float4(metrics, 0.0, 0.0);
    result.second = float4(distanceSquared, expectedImportance, receiverCosine, 0.0);
    result.third = float4(receiverNormal, 0.0);
}

void primeCheckLightBranchTargets(
        inout PrimeTransportResult result,
        float4 first,
        float2 scales)
{
    float2 firstMetrics = first.xy;
    float2 secondMetrics = first.zw;
    float probability = primeLightBranchProbability(firstMetrics, secondMetrics);
    float reverse = primeLightBranchProbability(secondMetrics, firstMetrics);
    float2 scaledFirst = firstMetrics * scales;
    float2 scaledSecond = secondMetrics * scales;
    float scaledProbability = primeLightBranchProbability(scaledFirst, scaledSecond);
    primeTransportClassify(result.mask, float3(
            probability, reverse, scaledProbability));
    if (!(probability >= 0.0) || !(reverse >= 0.0) || !(scaledProbability >= 0.0)
            || !primeTestClose(probability + reverse, 1.0, 2.0e-7, 2.0e-6)
            || !primeTestClose(probability, scaledProbability, 2.0e-7, 2.0e-6)) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    result.first = float4(firstMetrics, secondMetrics);
    result.second = float4(probability, reverse, scaledProbability, 0.0);
    result.third = float4(scaledFirst, scaledSecond);
}

void primeCheckOfflineSurfaceScratch(
        inout PrimeTransportResult result,
        uint4 control,
        float4 first,
        float4 second,
        float4 third,
        float4 fourth,
        float4 fifth)
{
    SurfaceInteraction surface;
    surface.t = first.x;
    surface.geometricNormal = first.yzw;
    surface.hitKind = PRIME_HIT_SURFACE;
    surface.materialControl = control.y & PRIME_SURFACE_STORAGE_MATERIAL_MASK;
    surface.sectionIndex = control.z;
    surface.emitterIndex = control.w;
    surface.baseColor = second.xyz;
    surface.textureLod = 0.0;
    surface.opacity = second.w;
    surface.shadingNormal = third.xyz;
    surface.roughness = third.w;
    surface.opticalControl = asuint(fourth.w) & PRIME_SURFACE_STORAGE_OPTICAL_MASK;
    surface.motionZFlags = asuint(fifth.w);
    surface.adjacentBaseColor = fourth.xyz;
    surface.adjacentInterfaceControl = (control.y ^ control.z)
            & PRIME_SURFACE_STORAGE_ADJACENT_MASK;
    surface.position = fifth.xyz;
    surface.mediumId = 0xfedcu;
    surface.adjacentMediumId = 0x89abu;

    PrimeSurfaceStorageRecord packed = primePackSurfaceStorageRecord(surface);
    SurfaceInteraction unpacked = primeUnpackSurfaceStorageRecord(packed);
    PrimeSurfaceStorageRecord roundTrip = primePackSurfaceStorageRecord(unpacked);
    bool exact = all(packed.geometry == roundTrip.geometry)
            && all(packed.identityBaseX == roundTrip.identityBaseX)
            && all(packed.baseYzOpacityRoughness == roundTrip.baseYzOpacityRoughness)
            && all(packed.opticalShadingNormal == roundTrip.opticalShadingNormal)
            && all(packed.adjacentBasePositionX == roundTrip.adjacentBasePositionX)
            && all(packed.positionYzMotionAdjacent
                    == roundTrip.positionYzMotionAdjacent)
            && unpacked.mediumId == surface.mediumId
            && unpacked.adjacentMediumId == surface.adjacentMediumId
            && unpacked.adjacentInterfaceControl == surface.adjacentInterfaceControl
            && unpacked.materialControl == surface.materialControl
            && unpacked.opticalControl == surface.opticalControl
            && asuint(unpacked.textureLod) == 0u
            && primeSurfaceTriangleIndex(unpacked)
                    == (surface.motionZFlags & PRIME_SURFACE_TRIANGLE_MASK);
    if (!exact) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = asfloat(packed.geometry);
    result.second = asfloat(uint4(
            packed.adjacentBasePositionX.xyz, primeSurfaceTriangleIndex(unpacked)));
    result.third = float4(unpacked.position, unpacked.textureLod);
}

void primeCheckOfflineQueueLayout(
        inout PrimeTransportResult result,
        uint pixelCount,
        uint queue,
        uint entry)
{
    uint stageEnd = primeOfflineStageWords(pixelCount);
    uint commandWord = primeOfflineQueueCommandWord(pixelCount, queue);
    uint indexBegin = primeOfflineQueueCommandWord(
            pixelCount, PRIME_WAVEFRONT_QUEUE_COUNT);
    uint indexWord = primeOfflineQueueWord(
            pixelCount, queue, entry);
    uint indexEnd = indexBegin
            + pixelCount * PRIME_WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL;
    uint surfaceEnd = pixelCount
            * (PRIME_OFFLINE_WAVEFRONT_SURFACE_RECORD_SIZE / 4u);
    uint stagedLightEnd = surfaceEnd
            + pixelCount * (PRIME_OFFLINE_STAGED_LIGHT_RECORD_SIZE / 4u);
    bool exact = commandWord == stageEnd
                    + queue * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u)
            && indexBegin == stageEnd
                    + PRIME_WAVEFRONT_QUEUE_COUNT
                            * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u)
            && indexWord == indexBegin + queue * pixelCount + entry
            && indexWord >= indexBegin
            && indexWord < indexEnd
            && surfaceEnd <= stagedLightEnd
            && stagedLightEnd == stageEnd;
    if (!exact) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = asfloat(uint4(
            stageEnd, commandWord, indexBegin, indexWord));
    result.second = asfloat(uint4(indexEnd, pixelCount, queue, entry));
}

void primeCheckOfflineTransportStorage(
        inout PrimeTransportResult result,
        uint4 control,
        float4 first,
        float4 second,
        float4 third,
        float4 fourth,
        float4 fifth)
{
    uint count = control.w & 0x3u;
    bool active = (control.w & 0x4u) != 0u;
    uint bounce = (control.w >> 8u) & PRIME_OFFLINE_PATH_BYTE_MASK;
    uint flags = (control.w >> 16u) & PRIME_OFFLINE_PATH_FLAGS_MASK;
    PathState path;
    path.physicalOrigin = first.xyz;
    path.previousBsdfPdf = first.w;
    path.rayDirection = second.xyz;
    path.etaScale = second.w;
    path.throughput = third.xyz;
    path.previousLightNormal = float3(third.w, fourth.w, fifth.w);
    path.sourcePrimitive = control.yz;
    path.control = primeMakePathStateControl(bounce, flags);
    path.sampleSeed = 0u;
    PrimeOpenPbrVolumeStack stack;
    stack.count = count;
    stack.values[0].extinction = fourth.xyz;
    stack.values[0].albedo = float3(0.0);
    stack.values[0].anisotropy = 0.0;
    stack.values[0].mediumId = control.y & 0xffffu;
    stack.values[0].iorEncoding = (control.y >> 16u) & 0x1ffu;
    stack.values[0].ior = primeDecodeMediumIor(stack.values[0].iorEncoding);
    stack.values[1].extinction = fifth.xyz;
    stack.values[1].albedo = float3(0.0);
    stack.values[1].anisotropy = 0.0;
    stack.values[1].mediumId = control.z & 0xffffu;
    stack.values[1].iorEncoding = (control.z >> 16u) & 0x1ffu;
    stack.values[1].ior = primeDecodeMediumIor(stack.values[1].iorEncoding);

    PrimeOfflineTransportRecord packed =
            primePackOfflineTransport(path, stack, active);
    PathState restoredPath;
    primeUnpackOfflineTransportPath(packed, restoredPath);
    PrimeOpenPbrVolumeStack restoredStack =
            primeUnpackOfflineTransportVolumeStack(packed);
    PrimeOfflineTransportRecord roundTrip = primePackOfflineTransport(
            restoredPath, restoredStack, primeOfflineTransportIsActive(packed));
    bool exact = all(packed.physicalOriginAndPreviousBsdfPdf
                        == roundTrip.physicalOriginAndPreviousBsdfPdf)
            && all(packed.sourceStateAndMediumIds
                        == roundTrip.sourceStateAndMediumIds)
            && all(packed.rayDirectionAndEtaScale
                        == roundTrip.rayDirectionAndEtaScale)
            && all(packed.throughputAndPreviousLightNormalX
                        == roundTrip.throughputAndPreviousLightNormalX)
            && all(packed.medium0AndPreviousLightNormalY
                        == roundTrip.medium0AndPreviousLightNormalY)
            && all(packed.medium1AndPreviousLightNormalZ
                        == roundTrip.medium1AndPreviousLightNormalZ)
            && all(asuint(restoredPath.physicalOrigin) == asuint(path.physicalOrigin))
            && asuint(restoredPath.previousBsdfPdf) == asuint(path.previousBsdfPdf)
            && all(asuint(restoredPath.rayDirection) == asuint(path.rayDirection))
            && asuint(restoredPath.etaScale) == asuint(path.etaScale)
            && all(asuint(restoredPath.throughput) == asuint(path.throughput))
            && all(asuint(restoredPath.previousLightNormal)
                        == asuint(path.previousLightNormal))
            && all(restoredPath.sourcePrimitive == path.sourcePrimitive)
            && primePathBounce(restoredPath.control) == bounce
            && primePathFlags(restoredPath.control) == flags
            && restoredStack.count == count
            && restoredStack.values[0].mediumId == stack.values[0].mediumId
            && restoredStack.values[1].mediumId == stack.values[1].mediumId
            && restoredStack.values[0].iorEncoding == stack.values[0].iorEncoding
            && restoredStack.values[1].iorEncoding == stack.values[1].iorEncoding
            && all(asuint(restoredStack.values[0].extinction)
                        == asuint(stack.values[0].extinction))
            && all(asuint(restoredStack.values[1].extinction)
                        == asuint(stack.values[1].extinction))
            && primeOfflineTransportIsActive(packed) == active;
    if (!exact) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    result.first = asfloat(packed.sourceStateAndMediumIds);
    result.second = asfloat(packed.medium0AndPreviousLightNormalY);
    result.third = asfloat(packed.medium1AndPreviousLightNormalZ);
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_TRANSPORT_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    float4 first = asfloat(primeTestInput[inputOffset + 1]);
    float4 second = asfloat(primeTestInput[inputOffset + 2]);
    float4 third = asfloat(primeTestInput[inputOffset + 3]);
    float4 fourth = asfloat(primeTestInput[inputOffset + 4]);
    float4 fifth = asfloat(primeTestInput[inputOffset + 5]);

    PrimeTransportResult result;
    result.mask = 0;
    result.first = float4(0.0);
    result.second = float4(0.0);
    result.third = float4(0.0);
    uint kind = control.x;
    if (kind == 0) {
        primeCheckProduct(result, first.xyz, second.xyz, first.w);
    } else if (kind == 1) {
        primeCheckMis(result, first.x, first.y, first.z);
    } else if (kind == 2) {
        primeCheckTransmittance(result, first.xyz, first.w);
    } else if (kind == 3) {
        primeCheckRoulette(result, first.xyz, first.w, second.x);
    } else if (kind == 4) {
        primeCheckAreaPdf(result, first.x, first.y, first.z);
    } else if (kind == 5) {
        primeCheckGlassVolume(result, first.xyz, first.w);
    } else if (kind == 6) {
        primeCheckShadowOpticalDepth(result, first, second);
    } else if (kind == 7) {
        primeCheckWavefrontMedium(result, control.yz, first, second);
    } else if (kind == 8) {
        primeCheckLightBranch(
                result,
                first,
                second,
                third,
                fourth,
                fifth.xyz,
                asfloat(control.yzw));
    } else if (kind == 9) {
        primeCheckAreaHitMis(
                result,
                (caseIndex & 1) != 0,
                (caseIndex & 2) != 0,
                first.x,
                first.y);
    } else if (kind == 10) {
        primeCheckOfflineRoulette(result, first.xyz, first.w, second.x);
    } else if (kind == 11) {
        primeCheckLightReceiverNormal(result, first.xyz);
    } else if (kind == 12) {
        primeCheckLightEmissionBound(
                result,
                control.y,
                first.xyz,
                second.xyz,
                third.xyz,
                fourth.xyz,
                fifth.xyz,
                fifth.w != 0.0);
    } else if (kind == 13) {
        primeCheckPointLightNodeMetric(result, first, second, third.xyz);
    } else if (kind == 14) {
        primeCheckPreviousDynamicSurface(result, first, second, third, fourth.xy);
    } else if (kind == 15) {
        primeCheckGlobalTriangleIndex(
                result, control, asuint(first), asuint(second.xy));
    } else if (kind == 16) {
        primeCheckPointLightNodeMetric(result, first, second, third.xyz);
    } else if (kind == 17) {
        primeCheckLightBranchTargets(result, first, third.xy);
    } else if (kind == 18) {
        primeCheckOfflineSurfaceScratch(
                result, control, first, second, third, fourth, fifth);
    } else if (kind == 19) {
        primeCheckOfflineQueueLayout(
                result, control.y, control.z, control.w);
    } else if (kind == 20) {
        primeCheckDetachedGuideMedium(
                result, control.w, control.yz, asuint(first.xy));
    } else if (kind == 21) {
        primeCheckOfflineTransportStorage(
                result, control, first, second, third, fourth, fifth);
    } else {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(result.mask, kind, caseIndex, 0);
    if (outputWords >= PRIME_TRANSPORT_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1] = control;
        primeTestOutput[outputOffset + 2] = asuint(first);
        primeTestOutput[outputOffset + 3] = asuint(second);
        primeTestOutput[outputOffset + 4] = asuint(third);
        primeTestOutput[outputOffset + 5] = asuint(fourth);
        primeTestOutput[outputOffset + 6] = asuint(fifth);
        primeTestOutput[outputOffset + 7] = asuint(result.first);
        primeTestOutput[outputOffset + 8] = asuint(result.second);
        primeTestOutput[outputOffset + 9] = asuint(result.third);
    }
}
