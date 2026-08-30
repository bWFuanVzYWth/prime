#language slang 2026
module "service/light/area.slang";

import "contract/light_sample.slang";
import "service/light/area_sample.slang";
import "service/light/tree_select.slang";

public AreaLightSample primeSampleAreaLight(
        float3 surfacePosition,
        float3 receiverNormal,
        float2 treeSample,
        float2 positionSample) {
    AreaLightSelection selection = primeSelectAreaLight(
            surfacePosition, receiverNormal, treeSample.x);
    return primeSampleAreaLightSelection(
            surfacePosition,
            receiverNormal,
            selection,
            treeSample.y,
            positionSample);
}
