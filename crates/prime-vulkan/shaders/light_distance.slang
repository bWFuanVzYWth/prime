#language slang 2026
module "light_distance.slang";

// Returns the first-child probability for a receiver-oriented p / r^2 proposal. Scaling both
// factors before cross multiplication preserves the distribution without overflowing.
public float primeLightBranchProbability(float2 firstMetrics, float2 secondMetrics) {
    float firstPower = max(firstMetrics.y, 0.0);
    float secondPower = max(secondMetrics.y, 0.0);
    float powerScale = max(firstPower, secondPower);
    if (!(powerScale > 0.0))
        return -1.0;
    firstPower /= powerScale;
    secondPower /= powerScale;

    float distanceScale = max(firstMetrics.x, secondMetrics.x);
    if (distanceScale == 0.0) {
        return firstPower / (firstPower + secondPower);
    }
    float firstScore = firstPower * (secondMetrics.x / distanceScale);
    float secondScore = secondPower * (firstMetrics.x / distanceScale);
    float score = firstScore + secondScore;
    return score > 0.0 ? firstScore / score : -1.0;
}
