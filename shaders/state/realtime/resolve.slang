#language slang 2026
module "state/realtime/resolve.slang";

import "contract/realtime/guide.slang";
import "state/realtime/contract.slang";
import "state/realtime/guide.slang";
import "state/realtime/transparent.slang";

import "contract/realtime/transparent.slang";
import "contract/realtime/resolved.slang";
import "service/reconstruct/ordinary.slang";
import "service/reconstruct/resolved.slang";

public PrimeResolvedSample primeResolveTransparentWavefrontResult(uint2 pixel) {
    uint transmissionControl = primeWavefrontBranchControl(primeWavefrontIndex(pixel, 0u));
    uint reflectionControl = primeWavefrontBranchControl(primeWavefrontIndex(pixel, 1u));
    PrimeTransparentBranchResult transmission =
            primeLoadTransparentBranchIntermediate(
                    pixel, true, transmissionControl);
    PrimeTransparentBranchResult reflection =
            primeLoadTransparentBranchIntermediate(
                    pixel, false, reflectionControl);
    PrimeDenoiserGuides visibleGuides =
            primeLoadWavefrontVisibleGuides(
                    pixel,
                    transmission,
                    reflection);
    primeFinalizeTransparentBranchGuide(
            transmissionControl, transmission);
    primeFinalizeTransparentBranchGuide(
            reflectionControl, reflection);

    PrimeResolvedSample result = primeResolveOrdinarySample(
            primeEmptyOrdinarySample());
    result.radiance.diffuse = transmission.diffuseRadiance;
    result.radiance.specular = transmission.specularRadiance;
    result.radiance.stable = float3(0.0);
    result.radiance.unshadowedSun = float3(0.0);
    result.radiance.sunVisibility = 0.0;
    result.guides = visibleGuides;
    result.guides.diffuseHitDistance = transmission.firstHitDistance;
    result.guides.specularHitDistance = reflection.firstHitDistance;
    result.guides.sunPenumbra = 0.0;
    result.reflectionDiffuseRadiance = reflection.diffuseRadiance;
    result.reflectionSpecularRadiance = reflection.specularRadiance;
    result.transmissionGuides = transmission.guides;
    result.reflectionGuides = reflection.guides;
    result.transmissionGuides.primaryAreaDiffuse =
            result.guides.primaryAreaDiffuse;
    result.transmissionGuides.primaryAreaDirection =
            result.guides.primaryAreaDirection;
    result.reflectionGuides.primaryAreaSpecular =
            result.guides.primaryAreaSpecular;
    result.reflectionGuides.primaryAreaDirection =
            result.guides.primaryAreaDirection;
    result.transmissionAnchorDistance = transmission.anchorDistance;
    result.reflectionDirectionalGuide = reflection.directionalGuide;
    result.transparentPrimary = true;
    result.transparentReflectionOnly =
            (transmissionControl & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u;
    return result;
}
