#language slang 2026
module "math/light_tree.slang";

public static const float PRIME_LIGHT_RECEIVER_COSINE_FLOOR = 1.0 / 256.0;
public static const uint PRIME_LIGHT_DIRECTION_MODE_SHIFT = 30u;
public static const uint PRIME_LIGHT_DIRECTION_MODE_LOBES = 2u;
public static const uint PRIME_LIGHT_DIRECTION_MODE_FULL = 3u;
public static const uint PRIME_LIGHT_DIRECTION_OCT_MASK = 0x3ffu;
public static const uint PRIME_LIGHT_DIRECTION_LOBE_MASK = 0x1fu;

public float2 primeLightDirectionSignNotZero(float2 value) {
    return float2(value.x >= 0.0 ? 1.0 : -1.0, value.y >= 0.0 ? 1.0 : -1.0);
}

public float3 primeUnpackLightEmissionAxis(uint packedValue) {
    float2 encoded = float2(
            float(packedValue & PRIME_LIGHT_DIRECTION_OCT_MASK),
            float((packedValue >> 10u) & PRIME_LIGHT_DIRECTION_OCT_MASK))
            / 1023.0 * 2.0 - 1.0;
    float3 axis = float3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
    if (axis.z < 0.0) {
        axis.xy = (1.0 - abs(axis.yx)) * primeLightDirectionSignNotZero(axis.xy);
    }
    return normalize(axis);
}

public float primeLightEmissionAxisCosineBound(
        float3 boundsMin,
        float3 boundsMax,
        float3 point,
        float3 axis,
        float distanceSquared,
        float inverseDistance) {
    if (!(distanceSquared > 0.0)) {
        return 1.0;
    }
    float3 support = select(axis >= float3(0.0), boundsMin, boundsMax);
    float maximumProjection = dot(axis, point - support);
    if (!(maximumProjection > 0.0)) {
        return 0.0;
    }
    return distanceSquared > maximumProjection * maximumProjection
            ? maximumProjection * inverseDistance
            : 1.0;
}

public float primeLightExpandedEmissionConeBound(
        float axisCosineBound, float sineHalfAngle, float cosineHalfAngle) {
    if (axisCosineBound >= cosineHalfAngle) {
        return 1.0;
    }
    float sine = sqrt(max(1.0 - axisCosineBound * axisCosineBound, 0.0));
    return clamp(
            axisCosineBound * cosineHalfAngle + sine * sineHalfAngle,
            0.0,
            1.0);
}

public float primeLightEmissionLobe(uint packedValue, uint shift) {
    return float((packedValue >> shift) & PRIME_LIGHT_DIRECTION_LOBE_MASK) / 31.0;
}

public float primeLightEmitterCosineBoundWithInverse(
        float3 boundsMin,
        float3 boundsMax,
        float3 point,
        float distanceSquared,
        float inverseDistance,
        uint packedDirection) {
    uint mode = packedDirection >> PRIME_LIGHT_DIRECTION_MODE_SHIFT;
    if (mode == PRIME_LIGHT_DIRECTION_MODE_FULL) {
        return 1.0;
    }
    if (mode == PRIME_LIGHT_DIRECTION_MODE_LOBES) {
        if (!(distanceSquared > 0.0)) {
            return 1.0;
        }
        // Axis-aligned support shares one inverse distance; mixed nodes need no per-lobe SFU work.
        float3 positive = min(max(point - boundsMin, float3(0.0)) * inverseDistance, float3(1.0));
        float3 negative = min(max(boundsMax - point, float3(0.0)) * inverseDistance, float3(1.0));
        float result = primeLightEmissionLobe(packedDirection, 0u) * positive.x
                + primeLightEmissionLobe(packedDirection, 5u) * negative.x
                + primeLightEmissionLobe(packedDirection, 10u) * positive.y
                + primeLightEmissionLobe(packedDirection, 15u) * negative.y
                + primeLightEmissionLobe(packedDirection, 20u) * positive.z
                + primeLightEmissionLobe(packedDirection, 25u) * negative.z;
        return min(result, 1.0);
    }
    float3 axis = primeUnpackLightEmissionAxis(packedDirection);
    float sineHalfAngle = float((packedDirection >> 20u) & 0x3ffu) / 1023.0;
    float cosineHalfAngle = sqrt(max(1.0 - sineHalfAngle * sineHalfAngle, 0.0));
    float forward = primeLightExpandedEmissionConeBound(
            primeLightEmissionAxisCosineBound(
                    boundsMin, boundsMax, point, axis, distanceSquared, inverseDistance),
            sineHalfAngle,
            cosineHalfAngle);
    if (mode == 0u) {
        return forward;
    }
    float backward = primeLightExpandedEmissionConeBound(
            primeLightEmissionAxisCosineBound(
                    boundsMin, boundsMax, point, -axis, distanceSquared, inverseDistance),
            sineHalfAngle,
            cosineHalfAngle);
    // Two-sided power integrates both hemispheres, so either directional lobe owns half of it.
    return 0.5 * max(forward, backward);
}

public float primeLightEmitterCosineBound(
        float3 boundsMin,
        float3 boundsMax,
        float3 point,
        float distanceSquared,
        uint packedDirection) {
    float inverseDistance = distanceSquared > 0.0 ? rsqrt(distanceSquared) : 0.0;
    return primeLightEmitterCosineBoundWithInverse(
            boundsMin,
            boundsMax,
            point,
            distanceSquared,
            inverseDistance,
            packedDirection);
}

public float primeLightEmitterCosineEstimate(
        float3 directionFromLight,
        uint packedDirection) {
    uint mode = packedDirection >> PRIME_LIGHT_DIRECTION_MODE_SHIFT;
    if (mode == PRIME_LIGHT_DIRECTION_MODE_FULL) {
        return 1.0;
    }
    if (mode == PRIME_LIGHT_DIRECTION_MODE_LOBES) {
        float3 positive = max(directionFromLight, float3(0.0));
        float3 negative = max(-directionFromLight, float3(0.0));
        return min(
                primeLightEmissionLobe(packedDirection, 0u) * positive.x
                        + primeLightEmissionLobe(packedDirection, 5u) * negative.x
                        + primeLightEmissionLobe(packedDirection, 10u) * positive.y
                        + primeLightEmissionLobe(packedDirection, 15u) * negative.y
                        + primeLightEmissionLobe(packedDirection, 20u) * positive.z
                        + primeLightEmissionLobe(packedDirection, 25u) * negative.z,
                1.0);
    }
    float3 axis = primeUnpackLightEmissionAxis(packedDirection);
    float sineHalfAngle = float((packedDirection >> 20u) & 0x3ffu) / 1023.0;
    float cosineHalfAngle = sqrt(max(1.0 - sineHalfAngle * sineHalfAngle, 0.0));
    float forward = primeLightExpandedEmissionConeBound(
            dot(axis, directionFromLight), sineHalfAngle, cosineHalfAngle);
    if (mode == 0u) {
        return forward;
    }
    float backward = primeLightExpandedEmissionConeBound(
            dot(-axis, directionFromLight), sineHalfAngle, cosineHalfAngle);
    return 0.5 * max(forward, backward);
}

public float primeLightReceiverCosineBoundWithInverse(
        float3 boundsMin,
        float3 boundsMax,
        float3 point,
        float3 receiverNormal,
        float distanceSquared,
        float inverseDistance) {
    if (!(dot(receiverNormal, receiverNormal) > 0.0)) {
        return 1.0;
    }
    float3 support = select(
            receiverNormal >= float3(0.0), boundsMax, boundsMin);
    float maximumProjection = dot(receiverNormal, support - point);
    if (!(maximumProjection > 0.0)) {
        return 0.0;
    }
    float bound = distanceSquared > maximumProjection * maximumProjection
            ? maximumProjection * inverseDistance
            : 1.0;
    return bound;
}

public float primeLightReceiverCosineBound(
        float3 boundsMin,
        float3 boundsMax,
        float3 point,
        float3 receiverNormal,
        float distanceSquared) {
    float inverseDistance = distanceSquared > 0.0 ? rsqrt(distanceSquared) : 0.0;
    return primeLightReceiverCosineBoundWithInverse(
            boundsMin,
            boundsMax,
            point,
            receiverNormal,
            distanceSquared,
            inverseDistance);
}

public float primeLightReceiverCosineEstimate(
        float3 directionToLight,
        float3 receiverNormal) {
    return dot(receiverNormal, receiverNormal) > 0.0
            ? max(dot(receiverNormal, directionToLight), 0.0)
            : 1.0;
}

public float2 primeLightNodeMetrics(
        float3 centroid,
        float power,
        float3 point,
        float3 receiverNormal,
        uint packedEmissionDirection) {
    // A sharp centroid proposal preserves important-light selection in both tree levels.
    float3 delta = centroid - point;
    float distanceSquared = dot(delta, delta);
    float angularImportance = 1.0;
    if (distanceSquared > 0.0) {
        float3 directionToLight = delta * rsqrt(distanceSquared);
        float receiverEstimate = primeLightReceiverCosineEstimate(
                directionToLight, receiverNormal);
        float emitterEstimate = primeLightEmitterCosineEstimate(
                -directionToLight, packedEmissionDirection);
        angularImportance = clamp(receiverEstimate * emitterEstimate, 0.0, 1.0);
    }
    float weightedPower = max(power, 0.0)
            * (PRIME_LIGHT_RECEIVER_COSINE_FLOOR
                    + (1.0 - PRIME_LIGHT_RECEIVER_COSINE_FLOOR) * angularImportance);
    return float2(distanceSquared, weightedPower);
}

// Returns the first-child probability for a receiver-oriented p / r^2 proposal. Scaling both
// factors before cross multiplication preserves the distribution without overflowing.
public float primeLightBranchProbability(float2 firstMetrics, float2 secondMetrics) {
    float firstPower = max(firstMetrics.y, 0.0);
    float secondPower = max(secondMetrics.y, 0.0);
    float powerScale = max(firstPower, secondPower);
    if (!(powerScale > 0.0)) return -1.0;
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
