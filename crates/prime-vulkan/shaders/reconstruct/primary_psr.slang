#language slang 2026
module "reconstruct/primary_psr.slang";

public static const uint PRIME_PRIMARY_PSR_COUNT = 0x7fu;
public static const uint PRIME_PRIMARY_PSR_ODD = 1u << 7;
public static const uint PRIME_PRIMARY_PSR_MOTION = 1u << 8;
public static const uint PRIME_PRIMARY_PSR_REFRACTED = 1u << 9;
public static const uint PRIME_PRIMARY_PSR_INTERFACE_SPECULAR = 1u << 10;
public static const uint PRIME_PRIMARY_GUIDE_SEED = 1u << 11;

// Only the physical path length and an orthogonal reflection transform cross a shared query.
// Refracted paths use a documented path-length proxy, never a fabricated previous-frame point.
public struct PrimePrimaryPsr {
    public float4 directionLength;
    public float4 rotation;
    public uint flags;
};

public PrimePrimaryPsr primePrimaryPsrInitial() {
    return {float4(0.0), float4(0.0, 0.0, 0.0, 1.0), PRIME_PRIMARY_PSR_MOTION};
}

float4 primePrimaryPsrMultiply(float4 a, float4 b) {
    return float4(a.w * b.xyz + b.w * a.xyz + cross(a.xyz, b.xyz), a.w * b.w - dot(a.xyz, b.xyz));
}

public float3 primePrimaryPsrDirection(PrimePrimaryPsr state, float3 direction) {
    float3 twiceCross = 2.0 * cross(state.rotation.xyz, direction);
    float3 result =
            direction + state.rotation.w * twiceCross + cross(state.rotation.xyz, twiceCross);
    return (state.flags & PRIME_PRIMARY_PSR_ODD) != 0 ? -result : result;
}

public PrimePrimaryPsr primePrimaryPsrAppend(PrimePrimaryPsr state, float3 cameraPosition,
                                             float3 previousPosition, float3 position,
                                             float3 normal, bool reflection, bool refracted,
                                             bool staticGeometry) {
    uint count = state.flags & PRIME_PRIMARY_PSR_COUNT;
    if (count == 0) {
        float3 segment = position - cameraPosition;
        float distance = length(segment);
        state.directionLength = float4(distance > 0.0 ? segment / distance : float3(0.0), distance);
    } else {
        state.directionLength.w += length(position - previousPosition);
    }
    if (reflection) {
        float4 rotation = primePrimaryPsrMultiply(state.rotation, float4(normal, 0.0));
        state.rotation = rotation * rsqrt(dot(rotation, rotation));
        state.flags ^= PRIME_PRIMARY_PSR_ODD;
    }
    if (!staticGeometry)
        state.flags &= ~PRIME_PRIMARY_PSR_MOTION;
    if (refracted)
        state.flags |= PRIME_PRIMARY_PSR_REFRACTED;
    state.flags = (state.flags & ~PRIME_PRIMARY_PSR_COUNT) | (count + 1u);
    return state;
}

public bool primePrimaryPsrSurface(PrimePrimaryPsr state, float3 cameraPosition,
                                   float3 previousPosition, float3 position, float3 normal,
                                   out float3 virtualPosition, out float3 virtualNormal) {
    virtualPosition = position;
    virtualNormal = normal;
    if ((state.flags & PRIME_PRIMARY_PSR_COUNT) != 0) {
        float distance = state.directionLength.w + length(position - previousPosition);
        virtualPosition = cameraPosition + state.directionLength.xyz * distance;
        virtualNormal = primePrimaryPsrDirection(state, normal);
        if (!(distance > 0.0) || !(dot(state.directionLength.xyz, state.directionLength.xyz) > 0.0))
            return false;
    }
    return !any(isnan(virtualPosition)) && !any(isinf(virtualPosition)) &&
           !any(isnan(virtualNormal)) && !any(isinf(virtualNormal)) &&
           dot(virtualNormal, virtualNormal) > 1e-12;
}

public bool primePrimaryPsrMotionKnown(PrimePrimaryPsr state, bool terminalStatic) {
    return terminalStatic && (state.flags & PRIME_PRIMARY_PSR_MOTION) != 0 &&
           (state.flags & PRIME_PRIMARY_PSR_REFRACTED) == 0;
}
