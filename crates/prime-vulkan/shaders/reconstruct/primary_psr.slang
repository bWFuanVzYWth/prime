#language slang 2026
module "reconstruct/primary_psr.slang";

public static const uint PRIME_PRIMARY_PSR_COUNT = 0x7fu;
public static const uint PRIME_PRIMARY_PSR_ODD = 1u << 7;
public static const uint PRIME_PRIMARY_PSR_MOTION = 1u << 8;
public static const uint PRIME_PRIMARY_PSR_REFRACTED = 1u << 9;
public static const uint PRIME_PRIMARY_PSR_INTERFACE_SPECULAR = 1u << 10;
public static const uint PRIME_PRIMARY_GUIDE_SEED = 1u << 11;

// Camera-side unfolding F(x) = A*x + translation. A is quaternion rotation with parity.
// Reflection planes compose exactly; refracted endpoints use a local tangent-plane proxy.
public struct PrimePrimaryPsr {
    public float4 rotation;
    public float3 translation;
    public uint flags;
};

public PrimePrimaryPsr primePrimaryPsrInitial() {
    return {float4(0.0, 0.0, 0.0, 1.0), float3(0.0), PRIME_PRIMARY_PSR_MOTION};
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

public float3 primePrimaryPsrPoint(PrimePrimaryPsr state, float3 point) {
    return primePrimaryPsrDirection(state, point) + state.translation;
}

public PrimePrimaryPsr primePrimaryPsrAppend(PrimePrimaryPsr state, float3 position, float3 normal,
                                             bool reflection, bool refracted, bool staticGeometry) {
    uint count = state.flags & PRIME_PRIMARY_PSR_COUNT;
    if (reflection) {
        // F' = F o H for H(x) = (I - 2*n*n^T)*x + 2*n*dot(n, position).
        // Translation uses the OLD A, before the right-composed reflection updates it.
        // The caller supplies the canonical unit optical normal used to produce the ray.
        state.translation += primePrimaryPsrDirection(state, 2.0 * normal * dot(normal, position));
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
                                   float3 initialCameraDirection, float3 targetPosition,
                                   float3 targetGeometricNormal, float3 targetShadingNormal,
                                   out float3 virtualPosition, out float3 virtualNormal) {
    // An ordinary primary hit is already the actual camera-ray endpoint, including grazing hits.
    virtualPosition = targetPosition;
    virtualNormal = targetShadingNormal;
    if ((state.flags & PRIME_PRIMARY_PSR_COUNT) != 0) {
        float3 planePosition = primePrimaryPsrPoint(state, targetPosition);
        float3 planeNormal = primePrimaryPsrDirection(state, targetGeometricNormal);
        virtualNormal = primePrimaryPsrDirection(state, targetShadingNormal);
        // Anchor the transformed target plane to the actual sampled camera ray. This removes
        // safe-origin offsets from ideal mirrors and gives static refractive proxies zero motion.
        // For refraction it is a target-plane approximation, not inverse Snell correspondence.
        float denominator = dot(planeNormal, initialCameraDirection);
        if (!(abs(denominator) > 1e-4))
            return false;
        float distance = dot(planeNormal, planePosition - cameraPosition) / denominator;
        if (!(distance > 0.0))
            return false;
        virtualPosition = cameraPosition + initialCameraDirection * distance;
    }
    return !any(isnan(virtualPosition)) && !any(isinf(virtualPosition)) &&
           !any(isnan(virtualNormal)) && !any(isinf(virtualNormal)) &&
           dot(virtualNormal, virtualNormal) > 1e-12;
}

public bool primePrimaryPsrMotionKnown(PrimePrimaryPsr state, bool terminalStatic) {
    // Known static proxy geometry; exact for fixed planar reflection chains, approximate
    // for refracted target planes. Missing dynamic previous geometry remains unsupported.
    return terminalStatic && (state.flags & PRIME_PRIMARY_PSR_MOTION) != 0;
}
