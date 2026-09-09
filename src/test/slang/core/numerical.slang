// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "core/numerical.slang";

// Test-only classification and observation state; production shaders do not import diagnostics.

public static const uint PRIME_NUMERICAL_NAN = 1;
public static const uint PRIME_NUMERICAL_POSITIVE_INFINITY = 2;
public static const uint PRIME_NUMERICAL_NEGATIVE_INFINITY = 4;
public static const uint PRIME_NUMERICAL_FINITE_NEGATIVE = 8;
public static const uint PRIME_NUMERICAL_ABOVE_FP16 = 16;
public static const uint PRIME_NUMERICAL_ABOVE_UNIT = 128;
public static const uint PRIME_NUMERICAL_INVALID_DIRECTION = 256;
public static const float PRIME_NUMERICAL_FP16_MAX = 65504.0;
public static const float PRIME_NUMERICAL_FP16_DIRECTION_ERROR = 4.0e-3;

public static const uint PRIME_NUMERICAL_STAGE_UNSCOPED = 0;
public static const uint PRIME_NUMERICAL_STAGE_CAMERA = 1;
public static const uint PRIME_NUMERICAL_STAGE_TRACE = 2;
public static const uint PRIME_NUMERICAL_STAGE_SURFACE = 3;
public static const uint PRIME_NUMERICAL_STAGE_MEDIUM = 4;
public static const uint PRIME_NUMERICAL_STAGE_EMISSION = 5;
public static const uint PRIME_NUMERICAL_STAGE_DIRECT_LIGHT = 6;
public static const uint PRIME_NUMERICAL_STAGE_BSDF_EVALUATE = 7;
public static const uint PRIME_NUMERICAL_STAGE_BSDF_SAMPLE = 8;
public static const uint PRIME_NUMERICAL_STAGE_PATH_ADVANCE = 9;
public static const uint PRIME_NUMERICAL_STAGE_TRANSPARENT_BRANCH = 10;
public static const uint PRIME_NUMERICAL_STAGE_GUIDE = 11;
public static const uint PRIME_NUMERICAL_STAGE_ACCUMULATION = 12;
public static const uint PRIME_NUMERICAL_STAGE_FINAL_OUTPUT = 13;

public static const uint PRIME_NUMERICAL_FIELD_NON_FINITE = 1;
public static const uint PRIME_NUMERICAL_FIELD_NONNEGATIVE = 2;
public static const uint PRIME_NUMERICAL_FIELD_RADIANCE = 3;
public static const uint PRIME_NUMERICAL_FIELD_UNIT = 4;
public static const uint PRIME_NUMERICAL_FIELD_DIRECTION = 5;
public static const uint PRIME_NUMERICAL_FIELD_OUTPUT = 6;

// Dedicated test entries enable observations. Production adapters construct the disabled value;
// the resulting constant no-op state is eliminated from optimized production entry points.
public struct PrimeNumericalObservation
{
    public uint flags;
    public uint firstField;
};

public PrimeNumericalObservation primeNumericalObservationCreate(bool enabled)
{
    PrimeNumericalObservation result;
    result.flags = 0;
    result.firstField = enabled ? 0 : 0xffffffffu;
    return result;
}

public void primeObserveNumerical(
        inout PrimeNumericalObservation observation, uint flags, uint field)
{
    if (flags == 0 || observation.firstField == 0xffffffffu) return;
    if (observation.flags == 0) observation.firstField = field;
    observation.flags |= flags;
}

public uint primeClassifyNonFinite(float value)
{
    if (isnan(value)) return PRIME_NUMERICAL_NAN;
    if (!isinf(value)) return 0;
    return value > 0.0
            ? PRIME_NUMERICAL_POSITIVE_INFINITY
            : PRIME_NUMERICAL_NEGATIVE_INFINITY;
}

public uint primeClassifyNonFinite(float3 value)
{
    return primeClassifyNonFinite(value.x)
            | primeClassifyNonFinite(value.y)
            | primeClassifyNonFinite(value.z);
}

public uint primeClassifyNonnegative(float value)
{
    uint flags = primeClassifyNonFinite(value);
    return flags == 0 && value < 0.0
            ? PRIME_NUMERICAL_FINITE_NEGATIVE
            : flags;
}

public uint primeClassifyNonnegative(float3 value)
{
    return primeClassifyNonnegative(value.x)
            | primeClassifyNonnegative(value.y)
            | primeClassifyNonnegative(value.z);
}

public uint primeClassifyRadiance(float value)
{
    uint flags = primeClassifyNonnegative(value);
    if (flags != 0) return flags;
    if (value > PRIME_NUMERICAL_FP16_MAX) flags |= PRIME_NUMERICAL_ABOVE_FP16;
    return flags;
}

public uint primeClassifyRadiance(float3 value)
{
    return primeClassifyRadiance(value.x)
            | primeClassifyRadiance(value.y)
            | primeClassifyRadiance(value.z);
}

public uint primeClassifyUnit(float value)
{
    uint flags = primeClassifyNonnegative(value);
    return flags == 0 && value > 1.0
            ? PRIME_NUMERICAL_ABOVE_UNIT
            : flags;
}

public uint primeClassifyUnit(float3 value)
{
    return primeClassifyUnit(value.x)
            | primeClassifyUnit(value.y)
            | primeClassifyUnit(value.z);
}

public uint primeClassifyDirection(float3 value)
{
    uint flags = primeClassifyNonFinite(value);
    if (flags != 0) return flags;
    float lengthSquared = dot(value, value);
    return isnan(lengthSquared)
            || isinf(lengthSquared)
            || !(lengthSquared > 1.0e-12)
            || abs(lengthSquared - 1.0) > 1.0e-3
            ? PRIME_NUMERICAL_INVALID_DIRECTION
            : 0;
}

public uint primeClassifyOptionalDirection(float3 value)
{
    uint flags = primeClassifyNonFinite(value);
    if (flags != 0) return flags;
    return all(value == float3(0.0)) ? 0 : primeClassifyDirection(value);
}

public void primeObserveNonnegative(
        inout PrimeNumericalObservation observation, float value)
{
    primeObserveNumerical(
            observation,
            primeClassifyNonnegative(value),
            PRIME_NUMERICAL_FIELD_NONNEGATIVE);
}

public void primeObserveNonnegative(
        inout PrimeNumericalObservation observation, float3 value)
{
    primeObserveNumerical(
            observation,
            primeClassifyNonnegative(value),
            PRIME_NUMERICAL_FIELD_NONNEGATIVE);
}

public void primeObserveUnit(
        inout PrimeNumericalObservation observation, float3 value)
{
    primeObserveNumerical(
            observation,
            primeClassifyUnit(value),
            PRIME_NUMERICAL_FIELD_UNIT);
}

public void primeObserveDirection(
        inout PrimeNumericalObservation observation, float3 value)
{
    primeObserveNumerical(
            observation,
            primeClassifyDirection(value),
            PRIME_NUMERICAL_FIELD_DIRECTION);
}

