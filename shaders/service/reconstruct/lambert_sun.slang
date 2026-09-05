#language slang 2026
module "service/reconstruct/lambert_sun.slang";
import "prime_abi_types.slang";

import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "service/reconstruct/nrd/common.slang";

public void primeLambertWriteSun(uint2 pixel, float3 unshadowed, float hitDistance) {
    primeImageStore(primeNrdSunLighting, int2(pixel), float4(
            primeNrdClampRadiance(unshadowed, PRIME_NRD_FP16_MAX), float(hitDistance < 0.0)));
    float penumbra = hitDistance < 0.0 ? PRIME_NRD_FP16_MAX
            : min(0.5 * hitDistance * tan(PRIME_ATMOSPHERE_SUN_ANGULAR_RADIUS_RADIANS), 32768.0);
    primeImageStore(primeNrdSunPenumbra, int2(pixel), float4(penumbra));
}

