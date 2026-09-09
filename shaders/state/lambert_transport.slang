// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/lambert_transport.slang";
import "prime_lambert_contract.slang";
import "contract/lambert.slang";
import "state/lambert.slang";
import "model/lambert/medium.slang";

public PrimeLambertMedium primeLambertLoadMedium(uint index) {
    PrimeLambertMedium medium;
    medium.outer = float3(primeLambertPaths[index].origin.w,
            primeLambertPaths[index].direction.w, primeLambertPaths[index].throughput.w);
    medium.inner = primeLambertPaths[index].innerMedium.xyz;
    medium.ids = asuint(primeLambertPaths[index].albedo.w);
    medium.count = primeLambertPaths[index].identity.w >> PRIME_LAMBERT_MEDIUM_SHIFT;
    return medium;
}
public void primeLambertStoreMedium(uint index, PrimeLambertMedium medium) {
    primeLambertPaths[index].origin.w = medium.outer.x;
    primeLambertPaths[index].direction.w = medium.outer.y;
    primeLambertPaths[index].throughput.w = medium.outer.z;
    primeLambertPaths[index].innerMedium.xyz = medium.inner;
    primeLambertPaths[index].albedo.w = asfloat(medium.ids);
    primeLambertPaths[index].identity.w = (primeLambertPaths[index].identity.w & 15u)
            | (medium.count << PRIME_LAMBERT_MEDIUM_SHIFT);
}
