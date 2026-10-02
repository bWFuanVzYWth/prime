// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026

module "math/transport.slang";

public static const uint PRIME_RUSSIAN_ROULETTE_START = 1;

// Budget counts illuminated surface vertices, including the primary hit.
public bool primePathHasContinuation(uint vertex, uint budget) {
    return vertex + 1u < budget;
}

public float primeProductOver(float first, float second, float denominator) {
    int firstExponent;
    int secondExponent;
    int denominatorExponent;
    float significand = frexp(first, firstExponent) * frexp(second, secondExponent) /
                        frexp(denominator, denominatorExponent);
    return ldexp(significand, firstExponent + secondExponent - denominatorExponent);
}

public float3 primeProductOver(float3 first, float3 second, float denominator) {
    int3 firstExponent;
    int3 secondExponent;
    int denominatorExponent;
    float3 significand = frexp(first, firstExponent) * frexp(second, secondExponent) /
                         frexp(denominator, denominatorExponent);
    return ldexp(significand, firstExponent + secondExponent - int3(denominatorExponent));
}

public float3 primeTripleProduct(float3 first, float3 second, float third) {
    int3 firstExponent;
    int3 secondExponent;
    int thirdExponent;
    float3 significand = frexp(first, firstExponent) * frexp(second, secondExponent) *
                         frexp(third, thirdExponent);
    return ldexp(significand, firstExponent + secondExponent + int3(thirdExponent));
}

// Slang's CPU ldexp lowers to x*exp2(e), so even a zero mantissa can become NaN
// when e overflows. A sanitized response has frexp exponent <=128. pdf>=4 bounds
// a terminal-vertex reciprocal to <=0.25 (exponent <=-1), keeping exp2(e) finite.
// The upper PDF bound keeps the reciprocal normal even on flush-to-zero GPUs.
// With continuation, MIS can underflow to zero (frexp exponent 0), so retain that path.
public bool primeCanSkipZeroNee(float3 source, float pdf, float3 throughput, bool continuation) {
    return !continuation && all(source == float3(0.0)) && !isnan(pdf) && !isinf(pdf) &&
           pdf >= 4.0 && pdf <= 1.0e37 && !any(isnan(throughput)) && !any(isinf(throughput));
}

public float primePowerHeuristicValue(float firstPdf, float secondPdf) {
    if (firstPdf <= 0.0)
        return 0.0;
    if (secondPdf <= 0.0)
        return 1.0;
    if (firstPdf >= secondPdf) {
        float ratio = secondPdf / firstPdf;
        return 1.0 / (1.0 + ratio * ratio);
    }
    float ratio = firstPdf / secondPdf;
    float ratioSquared = ratio * ratio;
    return ratioSquared / (1.0 + ratioSquared);
}

public float primePowerHeuristicOverPdfValue(float sampledPdf, float otherPdf) {
    if (sampledPdf <= 0.0)
        return 0.0;
    if (otherPdf <= 0.0)
        return 1.0 / sampledPdf;
    if (sampledPdf >= otherPdf) {
        float ratio = otherPdf / sampledPdf;
        return (1.0 / sampledPdf) / (1.0 + ratio * ratio);
    }
    float ratio = sampledPdf / otherPdf;
    return (ratio / otherPdf) / (1.0 + ratio * ratio);
}

public float primeAreaHitMisWeightValue(bool previousUsedAreaNee, bool previousCannotUseMis,
                                        float bsdfPdf, float lightPdf) {
    return !previousUsedAreaNee || previousCannotUseMis
                   ? 1.0
                   : primePowerHeuristicValue(bsdfPdf, lightPdf);
}

public float primeAreaSolidAnglePdfValue(float areaPdf, float distanceSquared, float lightCosine) {
    if (distanceSquared <= 0.0 || lightCosine <= 0.0 || areaPdf <= 0.0)
        return 0.0;
    return primeProductOver(areaPdf, distanceSquared, lightCosine);
}

public float3 primeSegmentTransmittance(float3 extinction, float distance) {
    return exp(-extinction * distance);
}

public float primeEtaScaleAfterScatter(float etaScale, float relativeEta, bool transmission) {
    return transmission ? etaScale * relativeEta * relativeEta : etaScale;
}

public float primeRussianRouletteMetric(float3 throughput, float etaScale) {
    return max(throughput.r, max(throughput.g, throughput.b)) * etaScale;
}

public float primeRussianRouletteSurvival(float3 throughput, float etaScale) {
    return min(max(primeRussianRouletteMetric(throughput, etaScale), 0.0), 1.0);
}

public bool primeRussianRouletteApplies(uint completedScatters, uint minimumCompletedScatters) {
    return completedScatters >= minimumCompletedScatters;
}

public float3 primeRussianRouletteReweight(float3 throughput, float survival) {
    return throughput / survival;
}
