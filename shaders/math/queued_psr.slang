#language slang 2026
module "math/queued_psr.slang";

public static const uint PRIME_QUEUED_PSR_CAPACITY = 64;
static const uint PRIME_QUEUED_PSR_COUNT_MASK = 0x7f;
static const uint PRIME_QUEUED_PSR_OVERFLOW = 0x80;
static const uint PRIME_QUEUED_PSR_ODD_REFLECTION = 0x100;

public struct PrimeQueuedPsrState
{
    public float4 firstDirectionLength;
    public float4 rotation;
    public uint control;
};

uint primeQueuedPsrControl(PrimeQueuedPsrState state)
{
    return state.control;
}

public uint primeQueuedPsrCount(PrimeQueuedPsrState state)
{
    return primeQueuedPsrControl(state) & PRIME_QUEUED_PSR_COUNT_MASK;
}

public bool primeQueuedPsrOverflowed(PrimeQueuedPsrState state)
{
    return (primeQueuedPsrControl(state) & PRIME_QUEUED_PSR_OVERFLOW) != 0;
}

public uint primeQueuedPsrStorageFlags(PrimeQueuedPsrState state)
{
    return (primeQueuedPsrOverflowed(state) ? 1u : 0u)
            | ((primeQueuedPsrControl(state)
                    & PRIME_QUEUED_PSR_ODD_REFLECTION) != 0u ? 2u : 0u);
}

public PrimeQueuedPsrState primeQueuedPsrWithRuntimeControl(
        PrimeQueuedPsrState state, uint count, uint storageFlags)
{
    state.control = min(count, PRIME_QUEUED_PSR_CAPACITY)
            | ((storageFlags & 1u) != 0u ? PRIME_QUEUED_PSR_OVERFLOW : 0u)
            | ((storageFlags & 2u) != 0u
                    ? PRIME_QUEUED_PSR_ODD_REFLECTION
                    : 0u);
    return state;
}

public PrimeQueuedPsrState primeEmptyQueuedPsrState()
{
    PrimeQueuedPsrState state;
    state.firstDirectionLength = float4(0.0);
    state.rotation = float4(0.0, 0.0, 0.0, 1.0);
    state.control = 0;
    return state;
}

float4 primeQueuedPsrQuaternionMultiply(float4 first, float4 second)
{
    return float4(
            first.w * second.xyz
                    + second.w * first.xyz
                    + cross(first.xyz, second.xyz),
            first.w * second.w - dot(first.xyz, second.xyz));
}

float3 primeQueuedPsrQuaternionRotate(float4 quaternion, float3 direction)
{
    float3 twiceCross = 2.0 * cross(quaternion.xyz, direction);
    return direction
            + quaternion.w * twiceCross
            + cross(quaternion.xyz, twiceCross);
}

public PrimeQueuedPsrState primeAppendQueuedPsrSegment(
        PrimeQueuedPsrState state,
        float3 cameraPosition,
        float3 position,
        float segmentLength,
        float3 normal,
        bool reflection)
{
    PrimeQueuedPsrState result = state;
    uint control = primeQueuedPsrControl(result);
    uint count = control & PRIME_QUEUED_PSR_COUNT_MASK;
    if (count == PRIME_QUEUED_PSR_CAPACITY) {
        result.control = control | PRIME_QUEUED_PSR_OVERFLOW;
        return result;
    }
    if (count == 0) {
        float3 firstSegment = position - cameraPosition;
        float firstLength = length(firstSegment);
        result.firstDirectionLength = float4(
                firstLength > 0.0 ? firstSegment / firstLength : float3(0.0),
                firstLength);
    } else {
        result.firstDirectionLength.w += segmentLength;
    }
    if (reflection) {
        float4 halfTurn = float4(normal, 0.0);
        float4 rotation = primeQueuedPsrQuaternionMultiply(result.rotation, halfTurn);
        float lengthSquared = dot(rotation, rotation);
        result.rotation = lengthSquared > 0.0
                ? rotation * rsqrt(lengthSquared)
                : float4(0.0, 0.0, 0.0, 1.0);
        control ^= PRIME_QUEUED_PSR_ODD_REFLECTION;
    }
    control = (control & ~PRIME_QUEUED_PSR_COUNT_MASK) | (count + 1);
    result.control = control;
    return result;
}

public PrimeQueuedPsrState primeAppendQueuedPsrState(
        PrimeQueuedPsrState state,
        float3 cameraPosition,
        float3 previousPosition,
        float3 position,
        float3 normal,
        bool reflection)
{
    return primeAppendQueuedPsrSegment(
            state,
            cameraPosition,
            position,
            length(position - previousPosition),
            normal,
            reflection);
}

public float3 primeQueuedPsrVirtualDirection(PrimeQueuedPsrState state, float3 direction)
{
    float3 transformed = primeQueuedPsrQuaternionRotate(state.rotation, direction);
    return (primeQueuedPsrControl(state) & PRIME_QUEUED_PSR_ODD_REFLECTION) != 0
            ? -transformed
            : transformed;
}

public bool primeBuildQueuedPsrGuideValueFromSegment(
        PrimeQueuedPsrState state,
        float targetSegmentLength,
        float3 targetNormal,
        out float3 virtualPosition,
        out float3 virtualNormal)
{
    // Slang requires definite assignment for out parameters. Callers use these only on success.
    virtualPosition = float3(0.0);
    virtualNormal = float3(0.0);
    if (primeQueuedPsrCount(state) == 0 || primeQueuedPsrOverflowed(state)) return false;
    float pathLength = state.firstDirectionLength.w + targetSegmentLength;
    if (!(dot(state.firstDirectionLength.xyz, state.firstDirectionLength.xyz) > 0.0)
            || !(pathLength > 0.0)) {
        return false;
    }
    virtualPosition = state.firstDirectionLength.xyz * pathLength;
    virtualNormal = primeQueuedPsrVirtualDirection(state, targetNormal);
    return true;
}

public bool primeBuildQueuedPsrGuideValue(
        PrimeQueuedPsrState state,
        float3 lastPosition,
        float3 target,
        float3 targetNormal,
        out float3 virtualPosition,
        out float3 virtualNormal)
{
    return primeBuildQueuedPsrGuideValueFromSegment(
            state,
            length(target - lastPosition),
            targetNormal,
            virtualPosition,
            virtualNormal);
}

bool primeQueuedPsrFinite(float value)
{
    return !isnan(value) && !isinf(value);
}

// Output reserves 65504 for the fp16 far/miss sentinel.
public static const float PRIME_QUEUED_PSR_MAX_ANCHOR_DISTANCE = 65503.0;

public float primeQueuedPsrAnchorDistanceValue(
        PrimeQueuedPsrState state,
        float3 cameraPosition,
        float3 target,
        float3 targetNormal)
{
    if (primeQueuedPsrOverflowed(state)) return -1.0;
    float3 targetDirection = target - cameraPosition;
    float targetLengthSquared = dot(targetDirection, targetDirection);
    float3 primaryDirection = primeQueuedPsrCount(state) == 0
            && targetLengthSquared > 1.0e-12
            ? targetDirection * rsqrt(targetLengthSquared)
            : state.firstDirectionLength.xyz;
    float denominator = dot(primaryDirection, targetNormal);
    if (!primeQueuedPsrFinite(denominator) || !(abs(denominator) > 1.0e-4)) {
        return -1.0;
    }
    float distance = dot(target - cameraPosition, targetNormal) / denominator;
    return primeQueuedPsrFinite(distance)
            && distance > 0.0
            && distance <= PRIME_QUEUED_PSR_MAX_ANCHOR_DISTANCE
            ? distance
            : -1.0;
}
