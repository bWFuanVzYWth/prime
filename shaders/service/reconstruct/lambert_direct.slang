// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/reconstruct/lambert_direct.slang";
import "state/lambert.slang";
import "contract/lambert.slang";
import "prime_lambert_contract.slang";
import "state/lambert_interface.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";
import "math/lambert_storage.slang";

public void primeLambertScratchStore(uint index, uint row, float4 value) {
    uint base = primeLambertScratchWord(index, row);
    [unroll] for (uint component = 0u; component < 4u; ++component)
        primeLambertQueue[base + component] = asuint(value[component]);
}
public void primeLambertBeginDirect(uint index) {
    if (!primeWritesNrdShInputs()) return;
    // Both guides consumed previous-interface geometry before these two rows become SH scratch.
    float3 emission = primeLambertPaths[index].radiance.rgb;
    float weight = primeNrdY(primeNrdDemodulate(emission, primeLambertPaths[index].albedo.rgb));
    primeLambertScratchStore(index, 1u, float4(emission, 0.0));
    primeLambertScratchStore(index, 2u, float4(0.0, 0.0, 0.0, weight));
}
public void primeLambertRecordDirect(uint index, float3 radiance, float3 direction) {
    if (!primeWritesNrdShInputs()) return;
    if ((primeLambertPaths[index].identity.w & PRIME_LAMBERT_REFLECTION) != 0u)
        direction = reflect(direction, primeLambertPaths[index].firstDirection.xyz);
    float weight = primeNrdY(primeNrdDemodulate(radiance, primeLambertPaths[index].albedo.rgb));
    primeLambertScratchStore(index, 1u, primeLambertInterfaceRow(index, 1u) + float4(radiance, 0.0));
    primeLambertScratchStore(index, 2u, primeLambertInterfaceRow(index, 2u) + float4(weight * direction, weight));
}
