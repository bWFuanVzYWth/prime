// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/direction.slang";

public static const float PRIME_FP16_DIRECTION_ERROR = 4.0e-3;

// Queue round-trips may perturb a valid unit direction within the known f16 bound. Restore only
// that narrow case; invalid or materially non-unit inputs remain visible to their caller.
public float3 primeRestoreFp16Direction(float3 value) {
    if (any(isnan(value)) || any(isinf(value))) return value;
    float lengthSquared = dot(value, value);
    return lengthSquared > 1.0e-12
                    && abs(lengthSquared - 1.0) <= PRIME_FP16_DIRECTION_ERROR
            ? value * rsqrt(lengthSquared)
            : value;
}
