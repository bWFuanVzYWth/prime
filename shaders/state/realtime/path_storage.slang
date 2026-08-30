#language slang 2026
module "state/realtime/path_storage.slang";

import "prime_abi_types.slang";

// Keep this leaf free of scene descriptors so the cross-dispatch codec can be tested without
// importing the complete trace backend.
public float4 primePackWavefrontPreviousLightNormalAndEtaScale(PathState path) {
    return float4(path.previousLightNormal, path.etaScale);
}

public void primeUnpackWavefrontPreviousLightNormalAndEtaScale(
        float4 stored,
        inout PathState path) {
    path.previousLightNormal = stored.xyz;
    path.etaScale = stored.w;
}
