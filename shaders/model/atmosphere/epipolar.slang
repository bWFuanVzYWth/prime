#language slang 2026
module "model/atmosphere/epipolar.slang";

import "prime_coordinate_contract.slang";

public static const float ATM_EPIPOLAR_LIMIT = 16384.0;
public static const float ATM_EPIPOLAR_EPSILON = 1.0e-6;

float atmDeterminant3(float3 first, float3 second, float3 third) {
    return dot(first, cross(second, third));
}

// Returns the four cofactors whose dot product with a leading column is the corresponding
// 4x4 determinant. Epipole projection needs only three rows of the inverse matrix; constructing
// those rows directly avoids a branch-heavy Gauss-Jordan inverse in every output invocation.
float4 atmColumnCofactors(float4 second, float4 third, float4 fourth) {
    return float4(
            atmDeterminant3(second.yzw, third.yzw, fourth.yzw),
            -atmDeterminant3(second.xzw, third.xzw, fourth.xzw),
            atmDeterminant3(second.xyw, third.xyw, fourth.xyw),
            -atmDeterminant3(second.xyz, third.xyz, fourth.xyz));
}

public struct AtmEpipolarSegment {
    public float2 entry;
    public float2 exit;
    public bool valid;
};

public struct AtmEpipolarRange {
    public float start;
    public float span;
    public float2 axis;
    public float extent;
    public bool parallel;
};

public float2 atmEpipolarProjectDirection(
        float4x4 inverseViewProjection,
        float3 worldDirection) {
    // Slang's SPIR-V matrix vector values follow the target's column convention even though
    // Prime selects row-major source layout to preserve the existing host ABI.
    float4 column0 = inverseViewProjection[0];
    float4 column1 = inverseViewProjection[1];
    float4 column2 = inverseViewProjection[2];
    float4 column3 = inverseViewProjection[3];
    float4 direction = float4(worldDirection, 0.0);
    float4 firstCofactors = atmColumnCofactors(column1, column2, column3);
    float determinant = dot(column0, firstCofactors);
    // Cramer's rule gives the required clip x/y/w numerators. Multiplying by det's sign instead
    // of dividing by det preserves both finite x/w ratios and the orientation at infinity.
    float determinantSign = determinant < 0.0 ? -1.0 : 1.0;
    float4 secondCofactors = atmColumnCofactors(column0, column2, column3);
    float4 fourthCofactors = atmColumnCofactors(column0, column1, column2);
    float4 clip = float4(
            dot(direction, firstCofactors),
            -dot(direction, secondCofactors),
            0.0,
            -dot(direction, fourthCofactors)) * determinantSign;
    float2 projected;
    if (abs(clip.w) > ATM_EPIPOLAR_EPSILON) {
        projected = clip.xy / clip.w;
    } else {
        float lengthSquared = dot(clip.xy, clip.xy);
        projected = lengthSquared > ATM_EPIPOLAR_EPSILON
                ? clip.xy * rsqrt(lengthSquared) * ATM_EPIPOLAR_LIMIT
                : float2(0.0);
    }
    if (any(isnan(projected)) || any(isinf(projected))) {
        return float2(0.0);
    }
    return clamp(
            projected,
            float2(-ATM_EPIPOLAR_LIMIT),
            float2(ATM_EPIPOLAR_LIMIT));
}

public void atmEpipolarSortPair(inout float first, inout float second) {
    if (first > second) {
        float temporary = first;
        first = second;
        second = temporary;
    }
}

public AtmEpipolarRange atmEpipolarVisibleRange(float2 epipoleNdc) {
    const float TWO_PI = 6.28318530717958647692;
    AtmEpipolarRange result;
    result.axis = float2(0.0);
    result.extent = 0.0;
    result.parallel = false;
    if (all(abs(epipoleNdc) <= float2(1.0))) {
        result.start = -3.14159265358979323846;
        result.span = TWO_PI;
        return result;
    }
    // Beyond this distance the finite epipole differs from parallel lines by
    // less than a pixel, while subtracting two f32 angles becomes less stable.
    if (max(abs(epipoleNdc.x), abs(epipoleNdc.y)) > 2048.0) {
        result.start = 0.0;
        result.span = 1.0;
        result.axis = normalize(-epipoleNdc);
        float2 perpendicular = float2(-result.axis.y, result.axis.x);
        result.extent = abs(perpendicular.x) + abs(perpendicular.y);
        result.parallel = true;
        return result;
    }

    float angle0 = fmod(
            atan2(-1.0 - epipoleNdc.y, -1.0 - epipoleNdc.x)
                    + TWO_PI,
            TWO_PI);
    float angle1 = fmod(
            atan2(-1.0 - epipoleNdc.y, 1.0 - epipoleNdc.x)
                    + TWO_PI,
            TWO_PI);
    float angle2 = fmod(
            atan2(1.0 - epipoleNdc.y, 1.0 - epipoleNdc.x)
                    + TWO_PI,
            TWO_PI);
    float angle3 = fmod(
            atan2(1.0 - epipoleNdc.y, -1.0 - epipoleNdc.x)
                    + TWO_PI,
            TWO_PI);
    atmEpipolarSortPair(angle0, angle1);
    atmEpipolarSortPair(angle2, angle3);
    atmEpipolarSortPair(angle0, angle2);
    atmEpipolarSortPair(angle1, angle3);
    atmEpipolarSortPair(angle1, angle2);

    float4 gaps = float4(
            angle1 - angle0,
            angle2 - angle1,
            angle3 - angle2,
            angle0 + TWO_PI - angle3);
    float largestGap = gaps.x;
    result.start = angle1;
    if (gaps.y > largestGap) {
        largestGap = gaps.y;
        result.start = angle2;
    }
    if (gaps.z > largestGap) {
        largestGap = gaps.z;
        result.start = angle3;
    }
    if (gaps.w > largestGap) {
        largestGap = gaps.w;
        result.start = angle0;
    }
    result.span = max(TWO_PI - largestGap, ATM_EPIPOLAR_EPSILON);
    return result;
}

public float atmEpipolarRangeCoordinate(
        AtmEpipolarRange range,
        float2 epipoleNdc,
        float2 screenNdc) {
    if (range.parallel) {
        float2 perpendicular = float2(-range.axis.y, range.axis.x);
        return clamp(
                dot(screenNdc, perpendicular)
                        / max(2.0 * range.extent, ATM_EPIPOLAR_EPSILON)
                        + 0.5,
                0.0,
                1.0);
    }
    const float TWO_PI = 6.28318530717958647692;
    float2 direction = screenNdc - epipoleNdc;
    float angle = atan2(direction.y, direction.x);
    float offset = fmod(angle - range.start + TWO_PI, TWO_PI);
    if (offset <= range.span) {
        return offset / range.span;
    }
    // A corner can round to the opposite side of the circular seam. Snap only
    // to the nearer interval endpoint instead of stretching that error across
    // the complete angular domain.
    float distanceToStart = TWO_PI - offset;
    float distanceToEnd = offset - range.span;
    return distanceToStart < distanceToEnd ? 0.0 : 1.0;
}

public AtmEpipolarSegment atmEpipolarRaySegment(float2 origin, float2 direction) {
    float entryDistance = 0.0;
    float exitDistance = ATM_EPIPOLAR_LIMIT * 4.0;
    bool valid = dot(direction, direction)
            > ATM_EPIPOLAR_EPSILON * ATM_EPIPOLAR_EPSILON;

    if (abs(direction.x) <= ATM_EPIPOLAR_EPSILON) {
        valid = valid && abs(origin.x) <= 1.0;
    } else {
        float2 distances = (float2(-1.0, 1.0) - origin.x) / direction.x;
        entryDistance = max(entryDistance, min(distances.x, distances.y));
        exitDistance = min(exitDistance, max(distances.x, distances.y));
    }
    if (abs(direction.y) <= ATM_EPIPOLAR_EPSILON) {
        valid = valid && abs(origin.y) <= 1.0;
    } else {
        float2 distances = (float2(-1.0, 1.0) - origin.y) / direction.y;
        entryDistance = max(entryDistance, min(distances.x, distances.y));
        exitDistance = min(exitDistance, max(distances.x, distances.y));
    }
    valid = valid
            && exitDistance >= entryDistance
            && exitDistance >= 0.0;

    AtmEpipolarSegment result;
    result.entry = origin + direction * max(entryDistance, 0.0);
    result.exit = origin + direction * exitDistance;
    result.valid = valid;
    return result;
}

public AtmEpipolarSegment atmEpipolarSliceSegment(
        float2 epipoleNdc,
        AtmEpipolarRange range,
        int slice,
        int sliceCount) {
    float sliceCoordinate =
            (float(slice) + 0.5) / float(sliceCount);
    if (range.parallel) {
        float2 perpendicular = float2(-range.axis.y, range.axis.x);
        float offset = lerp(
                -range.extent,
                range.extent,
                sliceCoordinate);
        float2 origin = perpendicular * offset - range.axis * 4.0;
        return atmEpipolarRaySegment(origin, range.axis);
    }
    float angle = range.start + range.span * sliceCoordinate;
    return atmEpipolarRaySegment(
            epipoleNdc,
            float2(cos(angle), sin(angle)));
}

public float2 atmEpipolarSliceNdc(
        float2 epipoleNdc,
        int2 coordinate,
        int2 dimensions) {
    AtmEpipolarRange range = atmEpipolarVisibleRange(epipoleNdc);
    AtmEpipolarSegment segment = atmEpipolarSliceSegment(
            epipoleNdc,
            range,
            coordinate.y,
            dimensions.y);
    float radialCoordinate =
            (float(coordinate.x) + 0.5) / float(dimensions.x);
    return segment.valid
            ? lerp(segment.entry, segment.exit, radialCoordinate)
            : float2(2.0);
}

public float atmEpipolarRadialCoordinate(
        float2 screenNdc,
        AtmEpipolarSegment segment) {
    float2 extent = segment.exit - segment.entry;
    float lengthSquared = dot(extent, extent);
    return lengthSquared > ATM_EPIPOLAR_EPSILON
            ? clamp(
                    dot(screenNdc - segment.entry, extent) / lengthSquared,
                    0.0,
                    1.0)
            : 0.0;
}

public float4 atmSampleEpipolarSlice(
        readonly RWTexture3D<float4> source,
        float2 screenNdc,
        float normalizedDepth,
        float2 epipoleNdc,
        AtmEpipolarRange range,
        int slice) {
    uint width;
    uint height;
    uint depth;
    source.GetDimensions(width, height, depth);
    int3 dimensions = int3(width, height, depth);
    AtmEpipolarSegment segment = atmEpipolarSliceSegment(
            epipoleNdc, range, slice, dimensions.y);
    float radial = atmEpipolarRadialCoordinate(screenNdc, segment);
    float2 coordinate = clamp(
            float2(radial, normalizedDepth), float2(0.0), float2(1.0))
            * float2(dimensions.x, dimensions.z) - float2(0.5);
    int2 lower = int2(floor(coordinate));
    float2 fraction = frac(coordinate);
    int2 maximum = dimensions.xz - int2(1);
    int2 p00 = clamp(lower, int2(0), maximum);
    int2 p10 = clamp(lower + int2(1, 0), int2(0), maximum);
    int2 p01 = clamp(lower + int2(0, 1), int2(0), maximum);
    int2 p11 = clamp(lower + int2(1), int2(0), maximum);
    float4 depth0 = lerp(
            source[int3(p00.x, slice, p00.y)],
            source[int3(p10.x, slice, p10.y)],
            fraction.x);
    float4 depth1 = lerp(
            source[int3(p01.x, slice, p01.y)],
            source[int3(p11.x, slice, p11.y)],
            fraction.x);
    return lerp(depth0, depth1, fraction.y);
}

public float4 atmSampleEpipolarWithRange(
        readonly RWTexture3D<float4> source,
        float2 screenUv,
        float normalizedDepth,
        float2 epipoleNdc,
        AtmEpipolarRange range) {
    uint width;
    uint height;
    uint depth;
    source.GetDimensions(width, height, depth);
    int3 dimensions = int3(width, height, depth);
    float2 screenNdc = primeCanonicalUvToClip(screenUv);
    const float twoPi = 6.28318530717958647692;
    float sliceCoordinate = atmEpipolarRangeCoordinate(
            range, epipoleNdc, screenNdc)
            * float(dimensions.y) - 0.5;
    int lowerSliceUnwrapped = int(floor(sliceCoordinate));
    float sliceFraction = frac(sliceCoordinate);
    bool wraps = !range.parallel
            && range.span >= twoPi - ATM_EPIPOLAR_EPSILON;
    int lowerSlice = wraps
            ? (lowerSliceUnwrapped % dimensions.y + dimensions.y) % dimensions.y
            : clamp(lowerSliceUnwrapped, 0, dimensions.y - 1);
    int upperSlice = wraps
            ? (lowerSlice + 1) % dimensions.y
            : clamp(lowerSliceUnwrapped + 1, 0, dimensions.y - 1);
    float4 lower = atmSampleEpipolarSlice(
            source, screenNdc, normalizedDepth, epipoleNdc, range, lowerSlice);
    float4 upper = atmSampleEpipolarSlice(
            source, screenNdc, normalizedDepth, epipoleNdc, range, upperSlice);
    return lerp(lower, upper, sliceFraction);
}

public float4 atmSampleEpipolar(
        readonly RWTexture3D<float4> source,
        float2 screenUv,
        float normalizedDepth,
        float2 epipoleNdc) {
    return atmSampleEpipolarWithRange(
            source,
            screenUv,
            normalizedDepth,
            epipoleNdc,
            atmEpipolarVisibleRange(epipoleNdc));
}
