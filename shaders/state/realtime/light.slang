#language slang 2026
module "state/realtime/light.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/realtime/guide.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "contract/light.slang";

// Member-wise access keeps cold PSR state out of ordinary continuations and prevents a storage
// record aggregate from remaining live across the integrator call tree.
public PrimeWavefrontTransportRecord primeLoadWavefrontTransportRecord(uint pathIndex) {
    PrimeWavefrontTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            primeWavefrontPaths[pathIndex]
                    .transport.physicalOriginAndPreviousBsdfPdf;
    record.sourceAndPathControl =
            primeWavefrontPaths[pathIndex].transport.sourceAndPathControl;
    record.rayDirectionAndDenoiserControl =
            primeWavefrontPaths[pathIndex]
                    .transport.rayDirectionAndDenoiserControl;
    record.throughputAndReserved =
            primeWavefrontPaths[pathIndex].transport.throughputAndReserved;
    record.medium0 = primeWavefrontPaths[pathIndex].transport.medium0;
    record.medium1 = primeWavefrontPaths[pathIndex].transport.medium1;
    record.previousLightNormalAndEtaScale =
            primeWavefrontPaths[pathIndex]
                    .transport.previousLightNormalAndEtaScale;
    return record;
}

public void primeStoreWavefrontTransportRecord(
        uint pathIndex,
        PrimeWavefrontTransportRecord record) {
    primeWavefrontPaths[pathIndex]
            .transport.physicalOriginAndPreviousBsdfPdf =
            record.physicalOriginAndPreviousBsdfPdf;
    primeWavefrontPaths[pathIndex].transport.sourceAndPathControl =
            record.sourceAndPathControl;
    primeWavefrontPaths[pathIndex]
            .transport.rayDirectionAndDenoiserControl =
            record.rayDirectionAndDenoiserControl;
    primeWavefrontPaths[pathIndex].transport.throughputAndReserved =
            record.throughputAndReserved;
    primeWavefrontPaths[pathIndex].transport.medium0 = record.medium0;
    primeWavefrontPaths[pathIndex].transport.medium1 = record.medium1;
    primeWavefrontPaths[pathIndex]
            .transport.previousLightNormalAndEtaScale =
            record.previousLightNormalAndEtaScale;
}

public void primeAccumulateDeferredDirectLight(
        inout PrimeIntegratorContext context,
        uint2 pixel,
        uint control,
        float3 contribution) {
    int2 coordinate = int2(pixel);
    bool transparent = (control & PRIME_WAVEFRONT_TRANSPARENT_BRANCH) != 0u;
    bool transmission = (control & PRIME_WAVEFRONT_TRANSMISSION_BRANCH) != 0u;
    bool guideEnabled = (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
    bool diffuse = transparent && !guideEnabled
            ? transmission
            : (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    float4 current;
    if (!transparent || transmission) {
        current = diffuse
                ? primeImageLoad(primeNrdNoisyDiffuse, coordinate)
                : primeImageLoad(primeNrdNoisySpecular, coordinate);
    } else if (primeWritesNrdShInputs()) {
        current = diffuse
                ? primeImageLoad(primeNrdReflectionNoisyDiffuse, coordinate)
                : primeImageLoad(primeNrdReflectionNoisySpecular, coordinate);
    } else {
        current = primeImageLoad(primeStableRadiance, coordinate);
    }

    current.rgb += contribution;
    current.rgb = primeNrdSanitizeRadiance(current.rgb);
    if (!transparent || transmission) {
        if (diffuse) {
            primeImageStore(primeNrdNoisyDiffuse, coordinate, current);
        } else {
            primeImageStore(primeNrdNoisySpecular, coordinate, current);
        }
    } else if (primeWritesNrdShInputs()) {
        if (diffuse) {
            primeImageStore(primeNrdReflectionNoisyDiffuse, coordinate, current);
        } else {
            primeImageStore(primeNrdReflectionNoisySpecular, coordinate, current);
        }
    } else {
        primeImageStore(primeStableRadiance, coordinate, current);
    }
}

public void primeAccumulatePrimaryDirectSplit(
        inout PrimeIntegratorContext context,
        uint2 pixel,
        PrimeDirectLightingSplit direct) {
    int2 coordinate = int2(pixel);
    float4 diffuse = primeImageLoad(primeNrdNoisyDiffuse, coordinate);
    float4 specular = primeImageLoad(primeNrdNoisySpecular, coordinate);
    diffuse.rgb += direct.diffuse;
    specular.rgb += direct.specular;
    diffuse.rgb = primeNrdSanitizeRadiance(diffuse.rgb);
    specular.rgb = primeNrdSanitizeRadiance(specular.rgb);
    primeImageStore(primeNrdNoisyDiffuse, coordinate, diffuse);
    primeImageStore(primeNrdNoisySpecular, coordinate, specular);
}

// Primary direct light for a deferred transparent guide is accumulated before its Shade pass.
// Keep the intermediate unsanitized so the final branch store preserves the original accumulation
// and sanitization order apart from permitted floating-point reassociation.
public void primeAccumulateTransparentPrimaryDirect(
        inout PrimeIntegratorContext context,
        uint2 pixel,
        bool transmissionBranch,
        float3 diffuseContribution,
        float3 specularContribution) {
    int2 coordinate = int2(pixel);
    float4 diffuse = transmissionBranch
            ? primeImageLoad(primeNrdNoisyDiffuse, coordinate)
            : primeImageLoad(primeNrdReflectionNoisyDiffuse, coordinate);
    float4 specular = transmissionBranch
            ? primeImageLoad(primeNrdNoisySpecular, coordinate)
            : primeImageLoad(primeNrdReflectionNoisySpecular, coordinate);
    diffuse.rgb += diffuseContribution;
    specular.rgb += specularContribution;
    if (transmissionBranch) {
        primeImageStore(primeNrdNoisyDiffuse, coordinate, diffuse);
        primeImageStore(primeNrdNoisySpecular, coordinate, specular);
    } else {
        primeImageStore(primeNrdReflectionNoisyDiffuse, coordinate, diffuse);
        primeImageStore(primeNrdReflectionNoisySpecular, coordinate, specular);
    }
}

public void primeStoreWavefrontPrimarySun(
        uint2 pixel,
        float3 unshadowed,
        float visibility,
        float penumbra) {
    int2 coordinate = int2(pixel);
    primeImageStore(
            primeNrdSunLighting,
            coordinate,
            float4(
                    primeNrdSanitizeRadiance(unshadowed),
                    primeNrdSanitizeUnit(visibility, 0.0)));
    primeImageStore(
            primeNrdSunPenumbra,
            coordinate,
            float4(primeNrdSanitizeHitDistance(penumbra)));
}
