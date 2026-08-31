#language slang 2026
module "service/material/tint.slang";

// Each immutable TintId names three row-major operator rows. The w lanes cache the same source
// tint converted to Rec.2020 for constant/baked materials.
// Binding 50 is reviewed in shaders/abi.json.
[[vk::binding(50, 0)]]
public ByteAddressBuffer primeTintOperators;

static const uint PRIME_TINT_OPERATOR_SIZE = 48u;

public struct PrimeTintOperator {
    public float4 row0;
    public float4 row1;
    public float4 row2;
};

PrimeTintOperator primeTintOperator(uint packedControlTintId) {
    uint tintId = packedControlTintId & 0x00ffffffu;
    uint offset = tintId * PRIME_TINT_OPERATOR_SIZE;
    PrimeTintOperator result;
    result.row0 = asfloat(primeTintOperators.Load4(offset));
    result.row1 = asfloat(primeTintOperators.Load4(offset + 16u));
    result.row2 = asfloat(primeTintOperators.Load4(offset + 32u));
    return result;
}

public float3 primeStaticTintColor(uint packedControlTintId) {
    PrimeTintOperator tint = primeTintOperator(packedControlTintId);
    return float3(tint.row0.w, tint.row1.w, tint.row2.w);
}

public float3 primeApplyStaticTint(
        float3 linearRec2020, PrimeTintOperator tint) {
    return clamp(
            float3(
                    dot(tint.row0.xyz, linearRec2020),
                    dot(tint.row1.xyz, linearRec2020),
                    dot(tint.row2.xyz, linearRec2020)),
            float3(0.0),
            float3(1.0));
}

public float3 primeApplyStaticTint(
        float3 linearRec2020, uint packedControlTintId) {
    return primeApplyStaticTint(
            linearRec2020, primeTintOperator(packedControlTintId));
}
