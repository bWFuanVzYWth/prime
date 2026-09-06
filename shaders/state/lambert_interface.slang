#language slang 2026
module "state/lambert_interface.slang";
import "contract/lambert.slang";
import "prime_lambert_contract.slang";
import "state/lambert.slang";
import "math/lambert_storage.slang";

public void primeLambertStoreInterface(uint index, PrimeLambertPayload hit, float fresnel) {
    uint base = primeLambertScratchWord(index, 0u);
    primeLambertPaths[index].firstDirection.xyz = hit.normal;
    float4 values[3] = { float4(hit.position, fresnel),
            float4(hit.previousPosition, asfloat(hit.triangle)), float4(hit.previousNormal, asfloat(hit.section)) };
    [unroll] for (uint row = 0u; row < 3u; ++row)
        [unroll] for (uint col = 0u; col < 4u; ++col)
            primeLambertQueue[base + 4u * row + col] = asuint(values[row][col]);
}
public float4 primeLambertInterfaceRow(uint index, uint row) {
    uint base = primeLambertScratchWord(index, row);
    return asfloat(uint4(primeLambertQueue[base], primeLambertQueue[base+1u],
            primeLambertQueue[base+2u], primeLambertQueue[base+3u]));
}
