// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/offline/context.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "state/realtime/medium.slang";
import "service/bsdf/support/volume_transition.slang";
import "state/offline/contract.slang";
import "state/offline/transport.slang";
import "bsdf/compact/contract/volume.slang";

public IntegratorRecord primeOfflineIntegrator() {
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    return integrator;
}

public PrimeOpenPbrVolume primeOfflineInitialMedium() {
    return (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterMedium()
            : primeVacuumMedium();
}

public PrimeOfflinePathRecord primeMakeOfflineRecord(
        PathState path,
        PrimeOpenPbrVolume medium,
        float3 radiance,
        float primaryDistance,
        float3 primaryAlbedo,
        uint primaryMaterialFlags,
        bool enabled) {
    PrimeOfflinePathRecord record;
    record.transport = primeMakeOfflineTransport(path, medium, enabled);
    record.radianceAndPrimaryDistance = float4(radiance, primaryDistance);
    record.primaryAlbedoAndMaterialFlags = float4(
            primaryAlbedo, asfloat(primaryMaterialFlags));
    return record;
}
