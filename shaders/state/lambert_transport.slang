// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/lambert_transport.slang";
import "prime_lambert_contract.slang";
import "contract/lambert.slang";
import "state/lambert.slang";
import "model/lambert/medium.slang";

public PrimeLambertMedium primeLambertLoadMedium(uint index) {
    PrimeLambertMedium medium;
    medium.extinction = float3(primeLambertPaths[index].origin.w,
            primeLambertPaths[index].direction.w, primeLambertPaths[index].throughput.w);
    medium.id = asuint(primeLambertPaths[index].albedo.w);
    return medium;
}
public void primeLambertStoreMedium(uint index, PrimeLambertMedium medium) {
    primeLambertPaths[index].origin.w = medium.extinction.x;
    primeLambertPaths[index].direction.w = medium.extinction.y;
    primeLambertPaths[index].throughput.w = medium.extinction.z;
    primeLambertPaths[index].albedo.w = asfloat(medium.id);
}
