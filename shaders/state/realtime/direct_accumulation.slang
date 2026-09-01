#language slang 2026
module "state/realtime/direct_accumulation.slang";

import "prime_realtime_abi.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";
import "state/realtime/contract.slang";
import "contract/light.slang";

public void primeAccumulateDeferredDirectLight(
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
// Keep the intermediate unsanitized so the final branch store preserves accumulation order.
public void primeAccumulateTransparentPrimaryDirect(
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
